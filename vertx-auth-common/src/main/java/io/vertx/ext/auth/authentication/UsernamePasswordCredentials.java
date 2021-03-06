/* ******************************************************************************
 * Copyright (c) 2020 Stephane Bastian
 *
 * This program and the accompanying materials are made available under the 2
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 3
 *
 * Contributors: 4
 *   Stephane Bastian - initial API and implementation
 ********************************************************************************/
package io.vertx.ext.auth.authentication;

import java.util.Objects;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;

/**
 * Credentials used by any {@link AuthenticationProvider} that requires username and password to perform its authentication
 *
 * @author <a href="mail://stephane.bastian.dev@gmail.com">Stephane Bastian</a>
 *
 */
@DataObject(generateConverter = true, publicConverter = false)
public class UsernamePasswordCredentials {

  private String password;
  private String username;

  protected UsernamePasswordCredentials() {}

  public UsernamePasswordCredentials(String username, String password) {
    setUsername(username);
    setPassword(password);
  }

  public UsernamePasswordCredentials(JsonObject jsonObject) {
    UsernamePasswordCredentialsConverter.fromJson(jsonObject, this);
  }

  public String getPassword() {
    return password;
  }

  public String getUsername() {
    return username;
  }

  public UsernamePasswordCredentials setPassword(String password) {
    this.password = Objects.requireNonNull(password);
    return this;
  }

  public UsernamePasswordCredentials setUsername(String username) {
    this.username = Objects.requireNonNull(username);
    return this;
  }

  public JsonObject toJson() {
    JsonObject result = new JsonObject();
    UsernamePasswordCredentialsConverter.toJson(this, result);
    return result;
  }

  @Override
  public String toString() {
    return toJson().encode();
  }
}
