<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * The type of a Salesforce org, as returned by the AppLink API.
 */
enum OrgType: string
{
    /** A Salesforce org. */
    case SalesforceOrg = 'SalesforceOrg';

    /** A Data 360 org. */
    case DataCloudOrg = 'DataCloudOrg';
}
