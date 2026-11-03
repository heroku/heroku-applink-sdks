package com.heroku.applink;

import java.util.Objects;

/**
 * A Salesforce user.
 *
 * @param id the ID of the user
 * @param username the username of the user
 */
public record User(String id, String username) {
  /**
   * Creates a user.
   *
   * @param id the ID of the user
   * @param username the username of the user
   */
  public User {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(username, "username");
  }
}
