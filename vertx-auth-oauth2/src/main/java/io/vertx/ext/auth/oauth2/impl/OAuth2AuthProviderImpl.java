/*
 * Copyright 2015 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.ext.auth.oauth2.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.oauth2.*;
import io.vertx.ext.auth.impl.jose.JWK;
import io.vertx.ext.auth.impl.jose.JWT;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.NoSuchKeyIdException;

import java.util.Collections;

/**
 * @author Paulo Lopes
 */
public class OAuth2AuthProviderImpl implements OAuth2Auth {

  private static final Logger LOG = LoggerFactory.getLogger(OAuth2AuthProviderImpl.class);

  private final Vertx vertx;
  private final OAuth2Options config;
  private final OAuth2API api;

  private JWT jwt = new JWT();
  private long updateTimerId = -1;
  private Handler<String> missingKeyHandler;

  public OAuth2AuthProviderImpl(Vertx vertx, OAuth2Options config) {
    this.vertx = vertx;
    this.config = config;
    this.api = new OAuth2API(vertx, config);
    // compute paths with variables, at this moment it is only relevant that
    // all variables are properly computed
    this.config.replaceVariables(true);
    this.config.validate();

    if (config.getPubSecKeys() != null) {
      for (PubSecKeyOptions pubSecKey : config.getPubSecKeys()) {
        jwt.addJWK(new JWK(pubSecKey));
      }
    }
  }

  @Override
  public OAuth2Auth jWKSet(Handler<AsyncResult<Void>> handler) {
    api.jwkSet(res -> {
      if (res.failed()) {
        handler.handle(Future.failedFuture(res.cause()));
      } else {
        if (updateTimerId != -1) {
          // cancel any running timer to avoid multiple updates
          // it is not important if the timer isn't active anymore

          // this could happen if both the user triggers the update and
          // there's a timer already in progress
          vertx.cancelTimer(updateTimerId);
        }
        final JsonObject json = res.result();
        JWT jwt = new JWT();
        JsonArray keys = json.getJsonArray("keys");
        for (Object key : keys) {
          try {
            jwt.addJWK(new JWK((JsonObject) key));
          } catch (RuntimeException e) {
            LOG.warn("Skipped unsupported JWK: " + e.getMessage());
          }
        }
        // swap
        this.jwt = jwt;
        // compute the next update if the server told us too
        if (json.containsKey("maxAge")) {
          // delay is in ms, while cache max age is sec
          final long delay = json.getLong("maxAge") * 1000;
          // salesforce (for example) sometimes disables the max-age as setting it to 0
          // for these cases we just cancel
          if (delay > 0) {
            this.updateTimerId = vertx.setPeriodic(delay, t ->
              jWKSet(autoUpdateRes -> {
                if (autoUpdateRes.failed()) {
                  LOG.warn("Failed to auto-update JWK Set", autoUpdateRes.cause());
                }
              }));
          } else {
            updateTimerId = -1;
          }
        }
        // return
        handler.handle(Future.succeededFuture());
      }
    });
    return this;
  }

  @Override
  public OAuth2Auth missingKeyHandler(Handler<String> handler) {
    this.missingKeyHandler = handler;
    return this;
  }

  public OAuth2Options getConfig() {
    return config;
  }

  @Override
  public void authenticate(JsonObject authInfo, Handler<AsyncResult<User>> handler) {
    authenticate(new Oauth2Credentials(authInfo), handler);
  }

  @Override
  public void authenticate(Oauth2Credentials authInfo, Handler<AsyncResult<User>> handler) {
    // if the authInfo object already contains a token validate it to confirm that it
    // can be reused, otherwise, based on the configured flow, request a new token
    // from the authority provider

    if (
      // authInfo contains a non null token
      authInfo.getAccessToken() != null) {

      // this validation can be done in 2 different ways:
      // 1) the token is a JWT and in this case if the provider is OpenId Compliant the token can be verified locally
      // 2) the token is an opaque string and we need to introspect it

      // if the JWT library is working in unsecure mode, local validation is not to be trusted

      final User user = createUser(authInfo.toJson());

      // the token is not a JWT or there are no loaded keys to validate
      if (!user.attributes().containsKey("accessToken") || jwt.isUnsecure()) {
        // the token is not in JWT format or this auth provider is not configured for secure JWTs
        // in this case we must rely on token introspection in order to know more about its state
        // attempt to create a token object from the given string representation

        // perform the introspection
        api
          .tokenIntrospection("access_token", user.principal().getString("access_token"), res -> {
            if (res.failed()) {
              handler.handle(Future.failedFuture(res.cause()));
              return;
            }

            final JsonObject json = res.result();

            // RFC7662 dictates that there is a boolean active field (however tokeninfo implementations may not return this)
            if (json.containsKey("active") && !json.getBoolean("active", false)) {
              handler.handle(Future.failedFuture("Inactive Token"));
              return;
            }

            // OPTIONALS

            // validate client id
            if (json.containsKey("client_id")) {
              // response included a client id. Match against config client id
              if (!config.getClientID().equals(json.getString("client_id"))) {
                // Client identifier for the OAuth 2.0 client that requested this token.
                handler.handle(Future.failedFuture("Wrong client_id"));
                return;
              }
            }

            // attempt to create a user from the json object
            final User newUser = createUser(json);

            // final step, verify if the user is not expired
            // this may happen if the user tokens have been issued for future use for example
            if (newUser.expired(config.getJWTOptions().getLeeway())) {
              handler.handle(Future.failedFuture("Used is expired."));
            } else {
              // basic validation passed, the token is not expired,
              // the spec mandates that that a few extra checks are performed
              validateUser(newUser, handler);
            }
          });

      } else {
        final JWTOptions jwtOptions = config.getJWTOptions();
        // a valid JWT token should have the access token value decoded
        // the token might be valid, but expired
        if (user.expired(jwtOptions.getLeeway())) {
          handler.handle(Future.failedFuture("Expired Token"));
        } else {
          // basic validation passed, the token is not expired,
          // the spec mandates that that a few extra checks are performed
          validateUser(user, handler);
        }
      }

    } else {
      // the authInfo object does not contain a token, so rely on the
      // configured flow to retrieve a token for the user
      // depending on the flow type the authentication will behave in different ways
      final JsonObject params = new JsonObject();
      switch (config.getFlow()) {
        case PASSWORD:
          if (authInfo.getUsername() != null && authInfo.getPassword() != null) {
            params
              .put("username", authInfo.getUsername())
              .put("password", authInfo.getPassword());
          } else {
            // the auth info object is incomplete, we can't proceed from here
            handler.handle(Future.failedFuture("PASSWORD flow requires {username, password}"));
            return;
          }
          break;
        case AUTH_CODE:
          if (authInfo.getCode() != null && authInfo.getRedirectUri() != null) {
            params.mergeIn(authInfo.toJson());
          } else {
            // the auth info object is incomplete, we can't proceed from here
            handler.handle(Future.failedFuture("AUTH_CODE flow requires {code, redirect_uri}"));
            return;
          }
          break;
        case CLIENT:
          params.mergeIn(authInfo.toJson());
          break;
        case AUTH_JWT:
          final JsonObject credentials = authInfo.toJson();

          params.mergeIn(credentials);
          params
            .put("assertion", jwt.sign(credentials, config.getJWTOptions()));
          break;
        default:
          handler.handle(Future.failedFuture("Current flow does not allow acquiring a token by the replay party"));
          return;
      }

      api.token(config.getFlow().getGrantType(), params, getToken -> {
        if (getToken.failed()) {
          handler.handle(Future.failedFuture(getToken.cause()));
        } else {

          // attempt to create a user from the json object
          final User newUser = createUser(getToken.result());

          // final step, verify if the user is not expired
          // this may happen if the user tokens have been issued for future use for example
          if (newUser.expired(config.getJWTOptions().getLeeway())) {
            handler.handle(Future.failedFuture("Used is expired."));
          } else {
            // basic validation passed, the token is not expired,
            // the spec mandates that that a few extra checks are performed
            validateUser(newUser, handler);
          }
        }
      });
    }
  }

  @Override
  public String authorizeURL(JsonObject params) {
    return api.authorizeURL(params);
  }

  @Override
  public OAuth2Auth refresh(User user, Handler<AsyncResult<User>> handler) {
    api.token(
      "refresh_token",
      new JsonObject()
        .put("refresh_token", user.principal().getString("refresh_token")),
      getToken -> {
        if (getToken.failed()) {
          handler.handle(Future.failedFuture(getToken.cause()));
        } else {
          // attempt to create a user from the json object
          final User newUser = createUser(getToken.result());
          // final step, verify if the user is not expired
          // this may happen if the user tokens have been issued for future use for example
          if (newUser.expired(config.getJWTOptions().getLeeway())) {
            handler.handle(Future.failedFuture("Used is expired."));
          } else {
            // basic validation passed, the token is not expired,
            // the spec mandates that that a few extra checks are performed
            validateUser(newUser, handler);
          }
        }
      });
    return this;
  }

  @Override
  public OAuth2Auth revoke(User user, String tokenType, Handler<AsyncResult<Void>> handler) {
    api.tokenRevocation(tokenType, user.principal().getString(tokenType), handler);
    return this;
  }

  @Override
  public OAuth2Auth userInfo(User user, Handler<AsyncResult<JsonObject>> handler) {
    api.userInfo(user.principal().getString("access_token"), handler);
    return this;
  }

  @Override
  public String endSessionURL(User user, JsonObject params) {
    return api.endSessionURL(user.principal().getString("id_token"), params);
  }

  OAuth2API api() {
    return api;
  }

  /**
   * Create a User object with some initial validations related to JWT.
   */
  private User createUser(JsonObject json) {
    // update the principal
    final User user = User.create(json);
    final long now = System.currentTimeMillis() / 1000;

    // compute the expires_at if any
    if (json.containsKey("expires_in")) {
      Long expiresIn;
      try {
        expiresIn = json.getLong("expires_in");
      } catch (ClassCastException e) {
        // for some reason someone decided to send a number as a String...
        expiresIn = Long.valueOf(json.getString("expires_in"));
      }
      // don't interfere with the principal object
      user.attributes()
        .put("iat", now)
        .put("exp", now + expiresIn);
    }

    // attempt to decode tokens if jwt keys are available
    if (!jwt.isUnsecure()) {
      if (json.containsKey("access_token")) {
        try {
          user.attributes()
            .put("accessToken", jwt.decode(json.getString("access_token")));

          // re-compute expires at if not present and access token has been successfully decoded from JWT
          if (!user.attributes().containsKey("exp")) {
            Long exp = user.attributes()
              .getJsonObject("accessToken").getLong("exp");

            if (exp != null) {
              user.attributes()
                .put("exp", exp);
            }
          }

          // root claim meta data for JWT AuthZ
          user.attributes()
            .put("rootClaim", "accessToken");

        } catch (NoSuchKeyIdException e) {
          // the JWT store has no knowledge about the key id on this token
          // if the user has specified a handler for this situation then it
          // shall be executed, otherwise just log as a typical validation
          if (missingKeyHandler != null) {
            missingKeyHandler.handle(e.id());
          } else {
            LOG.trace("Cannot decode access token:", e);
          }
        } catch (DecodeException | IllegalStateException e) {
          // explicitly catch and log. The exception here is a valid case
          // the reason is that it can be for several factors, such as bad token
          // or invalid JWT key setup, in that case we fall back to opaque token
          // which is the default operational mode for OAuth2.
          LOG.trace("Cannot decode access token:", e);
        }
      }

      if (json.containsKey("id_token")) {
        try {
          user.attributes()
            .put("idToken", jwt.decode(json.getString("id_token")));

          // re-compute expires at if not present and id token has been successfully decoded from JWT
          if (!user.attributes().containsKey("exp")) {
            Long exp = user.attributes()
              .getJsonObject("idToken").getLong("exp");

            if (exp != null) {
              user.attributes()
                .put("exp", exp);
            }
          }
        } catch (NoSuchKeyIdException e) {
          // the JWT store has no knowledge about the key id on this token
          // if the user has specified a handler for this situation then it
          // shall be executed, otherwise just log as a typical validation
          if (missingKeyHandler != null) {
            missingKeyHandler.handle(e.id());
          } else {
            LOG.trace("Cannot decode access token:", e);
          }
        } catch (DecodeException | IllegalStateException e) {
          // explicity catch and log. The exception here is a valid case
          // the reason is that it can be for several factors, such as bad token
          // or invalid JWT key setup, in that case we fall back to opaque token
          // which is the default operational mode for OAuth2.
          LOG.trace("Cannot decode id token:", e);
        }
      }
    }

    return user;
  }

  private void validateUser(User user, Handler<AsyncResult<User>> handler) {

    if (!user.attributes().containsKey("accessToken")) {
      // nothing else to do
      handler.handle(Future.succeededFuture(user));
      return;
    }

    // the user object is a JWT so we should validate it as mandated by OIDC
    final JWTOptions jwtOptions = config.getJWTOptions();

    // basic validation passed, the token is not expired,
    // the spec mandates that that a few extra checks are performed
    final JsonObject payload;

    try {
      payload = user.attributes().getJsonObject("accessToken");
    } catch (RuntimeException e) {
      handler.handle(Future.failedFuture("User accessToken isn't a JsonObject"));
      return;
    }

    if (jwtOptions.getAudience() != null) {
      JsonArray target;
      if (payload.getValue("aud") instanceof String) {
        target = new JsonArray().add(payload.getValue("aud", ""));
      } else {
        target = payload.getJsonArray("aud", new JsonArray());
      }

      if (Collections.disjoint(jwtOptions.getAudience(), target.getList())) {
        handler.handle(Future.failedFuture("Invalid JWT audience. expected: " + Json.encode(jwtOptions.getAudience())));
        return;
      }
    }

    if (jwtOptions.getIssuer() != null) {
      if (!jwtOptions.getIssuer().equals(payload.getString("iss"))) {
        handler.handle(Future.failedFuture("Invalid JWT issuer"));
        return;
      }
    }

    handler.handle(Future.succeededFuture(user));
  }

  @Override
  @Deprecated
  public OAuth2Auth decodeToken(String token, Handler<AsyncResult<AccessToken>> handler) {
    try {
      JsonObject json = jwt.decode(token);
      handler.handle(Future.succeededFuture(createAccessToken(json)));
    } catch (RuntimeException e) {
      handler.handle(Future.failedFuture(e));
    }
    return this;
  }

  @Override
  @Deprecated
  public OAuth2Auth introspectToken(String token, String tokenType, Handler<AsyncResult<AccessToken>> handler) {
    return this;
  }

  @Override
  @Deprecated
  public OAuth2FlowType getFlowType() {
    return config.getFlow();
  }

  @Override
  @Deprecated
  public OAuth2Auth rbacHandler(OAuth2RBAC rbac) {
    return this;
  }

  /**
   * Create a User object with some initial validations related to JWT.
   */
  @Deprecated
  private AccessToken createAccessToken(JsonObject json) {
    // update the principal
    final AccessToken user = new AccessTokenImpl(json, this);
    final long now = System.currentTimeMillis() / 1000;

    // compute the expires_at if any
    if (json.containsKey("expires_in")) {
      Long expiresIn;
      try {
        expiresIn = json.getLong("expires_in");
      } catch (ClassCastException e) {
        // for some reason someone decided to send a number as a String...
        expiresIn = Long.valueOf(json.getString("expires_in"));
      }
      // don't interfere with the principal object
      user.attributes()
        .put("iat", now)
        .put("exp", now + expiresIn);
    }

    // attempt to decode tokens
    if (json.getString("access_token") != null) {
      try {
        user.attributes()
          .put("accessToken", jwt.decode(json.getString("access_token")));

        // re-compute expires at if not present and access token has been successfully decoded from JWT
        if (!user.attributes().containsKey("exp")) {
          Long exp = user.attributes()
            .getJsonObject("accessToken").getLong("exp");

          if (exp != null) {
            user.attributes()
              .put("exp", exp);
          }
        }

        // root claim meta data for JWT AuthZ
        user.attributes()
          .put("rootClaim", "accessToken");

      } catch (NoSuchKeyIdException e) {
        // the JWT store has no knowledge about the key id on this token
        // if the user has specified a handler for this situation then it
        // shall be executed, otherwise just log as a typical validation
        if (missingKeyHandler != null) {
          missingKeyHandler.handle(e.id());
        } else {
          LOG.trace("Cannot decode access token:", e);
        }
      } catch (DecodeException | IllegalStateException e) {
        // explicity catch and log as trace. exception here is a valid case
        // the reason is that it can be for several factors, such as bad token
        // or invalid JWT key setup, in that case we fall back to opaque token
        // which is the default operational mode for OAuth2.
        LOG.trace("Cannot decode access token:", e);
      }
    }

    if (json.getString("id_token") != null) {
      try {
        user.attributes()
          .put("idToken", jwt.decode(json.getString("id_token")));
      } catch (NoSuchKeyIdException e) {
        // the JWT store has no knowledge about the key id on this token
        // if the user has specified a handler for this situation then it
        // shall be executed, otherwise just log as a typical validation
        if (missingKeyHandler != null) {
          missingKeyHandler.handle(e.id());
        } else {
          LOG.trace("Cannot decode access token:", e);
        }
      } catch (DecodeException | IllegalStateException e) {
        // explicity catch and log as trace. exception here is a valid case
        // the reason is that it can be for several factors, such as bad token
        // or invalid JWT key setup, in that case we fall back to opaque token
        // which is the default operational mode for OAuth2.
        LOG.trace("Cannot decode id token:", e);
      }
    }

    return user;
  }
}
