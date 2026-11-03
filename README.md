# Heroku AppLink SDKs

The Heroku AppLink SDKs let Heroku apps find out who invoked them from Salesforce, and look up the credentials of stored [Heroku AppLink](https://devcenter.heroku.com/articles/heroku-applink) authorizations. Apps call Salesforce APIs with the Salesforce client of their choice, using the org and credentials the SDK returns.

All SDKs implement the same SDK spec and offer the same features in the idiom of their language.

| Language | Package                               | Directory                                        |
| -------- | ------------------------------------- | ------------------------------------------------ |
| .NET     | `Heroku.AppLink`                      | [`heroku-applink-dotnet`](heroku-applink-dotnet) |
| Go       | `github.com/heroku/heroku-applink-go` | [`heroku-applink-go`](heroku-applink-go)         |
| Java     | `com.heroku:heroku-applink`           | [`heroku-applink-java`](heroku-applink-java)     |
| Node.js  | `@heroku/applink`                     | [`heroku-applink-nodejs`](heroku-applink-nodejs) |
| PHP      | `heroku/applink`                      | [`heroku-applink-php`](heroku-applink-php)       |
| Python   | `heroku-applink`                      | [`heroku-applink-python`](heroku-applink-python) |
| Ruby     | `heroku-applink`                      | [`heroku-applink-ruby`](heroku-applink-ruby)     |

Each SDK's README covers installation, usage, and development.

## Test vectors

Every SDK's test suite runs against the shared test vectors in [`test-vectors`](test-vectors), so all SDKs handle the same inputs the same way.
