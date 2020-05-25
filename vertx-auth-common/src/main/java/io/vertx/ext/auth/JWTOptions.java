/*
 * Copyright 2014 Red Hat, Inc.
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
package io.vertx.ext.auth;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Options related to creation of new tokens.
 *
 * If any expiresInMinutes, audience, subject, issuer are not provided, there is no default.
 * The jwt generated won't include those properties in the payload.
 *
 * Generated JWTs will include an iat claim by default unless noTimestamp is specified.
 *
 * @author Paulo Lopes
 */
@DataObject(generateConverter = true)
public class JWTOptions {

  private static final JsonObject EMPTY = new JsonObject(Collections.emptyMap());

  private int leeway = 0;
  private boolean ignoreExpiration;
  private String algorithm = "HS256";
  private JsonObject header = EMPTY;
  private boolean noTimestamp;
  private int expiresInSeconds;
  private List<String> audience;
  private String issuer;
  private String subject;
  private List<String> permissions;

  public JWTOptions() {

  }

  public JWTOptions(JsonObject json) {
    JWTOptionsConverter.fromJson(json, this);
  }

  public JsonObject toJSON() {
    final JsonObject json = new JsonObject();
    JWTOptionsConverter.toJson(this, json);
    return json;
  }

  public int getLeeway() {
    return leeway;
  }

  public JWTOptions setLeeway(int leeway) {
    this.leeway = leeway;
    return this;
  }

  public boolean isIgnoreExpiration() {
    return ignoreExpiration;
  }

  public JWTOptions setIgnoreExpiration(boolean ignoreExpiration) {
    this.ignoreExpiration = ignoreExpiration;
    return this;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public JWTOptions setAlgorithm(String algorithm) {
    this.algorithm = algorithm;
    return this;
  }

  public JsonObject getHeader() {
    return header;
  }

  public JWTOptions setHeader(JsonObject header) {
    this.header = header;
    return this;
  }

  public boolean isNoTimestamp() {
    return noTimestamp;
  }

  public JWTOptions setNoTimestamp(boolean noTimestamp) {
    this.noTimestamp = noTimestamp;
    return this;
  }

  public int getExpiresInSeconds() {
    return expiresInSeconds;
  }

  public JWTOptions setExpiresInSeconds(int expiresInSeconds) {
    this.expiresInSeconds = expiresInSeconds;
    return this;
  }

  public JWTOptions setExpiresInMinutes(int expiresInMinutes) {
    this.expiresInSeconds = expiresInMinutes * 60;
    return this;
  }

  public List<String> getAudience() {
    return audience;
  }

  public JWTOptions setAudience(List<String> audience) {
    this.audience = audience;
    return this;
  }

  public JWTOptions addAudience(String audience) {
    if (this.audience == null) {
      this.audience = new ArrayList<>();
    }
    this.audience.add(audience);
    return this;
  }

  public String getIssuer() {
    return issuer;
  }

  public JWTOptions setIssuer(String issuer) {
    this.issuer = issuer;
    return this;
  }

  public String getSubject() {
    return subject;
  }

  public JWTOptions setSubject(String subject) {
    this.subject = subject;
    return this;
  }

  /**
   * The permissions of this token.
   *
   * @param permissions the permissions for this token that will be used for AuthZ
   * @return fluent API
   */
  public JWTOptions setPermissions(List<String> permissions) {
    this.permissions = permissions;
    return this;
  }

  /**
   * Add a permission to this token.
   *
   * @param permission permission for this token that will be used for AuthZ
   * @return fluent API
   */
  public JWTOptions addPermission(String permission) {
    if (this.permissions == null) {
      this.permissions = new ArrayList<>();
    }
    this.permissions.add(permission);
    return this;
  }

  public List<String> getPermissions() {
    return permissions;
  }
}
