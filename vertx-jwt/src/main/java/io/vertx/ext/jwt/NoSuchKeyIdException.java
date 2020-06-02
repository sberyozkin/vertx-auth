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
package io.vertx.ext.jwt;

/**
 * No such KeyId exception is thrown when a JWT with a well known "kid" does not find a matching "kid" in the crypto
 * list.
 */
public final class NoSuchKeyIdException extends RuntimeException {

  private final String alg;
  private final String kid;

  public NoSuchKeyIdException(String alg) {
    this(alg, "<null>");
  }

  public NoSuchKeyIdException(String alg, String kid) {
    super("algorithm [" + alg + "]: " + kid);
    this.alg = alg;
    this.kid = kid;
  }

  /**
   * Returns the missing key id in the combined {@code ALGORITHM + '#' + KEY_ID} format.
   * @return the missing key id in the combined {@code ALGORITHM + '#' + KEY_ID} format.
   */
  public String id() {
    return alg + "#" + kid;
  }

  /**
   * Returns the missing key algorithm.
   * @return the missing key algorithm
   */
  public String algorithm() {
    return alg;
  }
  /**
   * Returns the missing key identifier.
   * @return the missing key identifier
   */
  public String keyId() {
    return kid;
  }
}
