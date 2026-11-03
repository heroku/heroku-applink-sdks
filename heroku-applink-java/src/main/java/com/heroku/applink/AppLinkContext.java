package com.heroku.applink;

/**
 * What every context has: the org and the user.
 *
 * <p>Code that only needs the org and the user accepts an {@code AppLinkContext}, so it works with
 * both kinds of context.
 */
public sealed interface AppLinkContext
    permits InvocationAppLinkContext, AuthorizationAppLinkContext {
  /**
   * Returns the org the context belongs to.
   *
   * @return the org
   */
  Org org();

  /**
   * Returns the user acting in the org.
   *
   * @return the user
   */
  User user();
}
