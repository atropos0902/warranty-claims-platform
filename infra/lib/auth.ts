import { RemovalPolicy, Stack, type StackProps } from 'aws-cdk-lib';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import type { Construct } from 'constructs';

type Props = StackProps & { stage: string; domainName: string };

/** What each machine may ask for. The assistant only reads, and no service has a scope that decides a claim. */
export const MACHINE_SCOPES = {
  claims: ['pricing/quote'],
  assistant: ['claims/read', 'pricing/quote'],
  migration: ['groups/move', 'pricing/compare'],
  evaluation: ['assistant/evaluate'],
} as const;

export type Machine = keyof typeof MACHINE_SCOPES;

export class AuthStack extends Stack {
  readonly pool: cognito.UserPool;
  readonly domain: cognito.UserPoolDomain;
  readonly webClient: cognito.UserPoolClient;
  readonly machines: Record<Machine, cognito.UserPoolClient>;

  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);
    this.pool = new cognito.UserPool(this, 'People', {
      selfSignUpEnabled: false,
      signInAliases: { email: true },
      mfa: cognito.Mfa.REQUIRED,
      mfaSecondFactor: { otp: true, sms: false },
      passwordPolicy: { minLength: 14 },
      removalPolicy: RemovalPolicy.RETAIN,
    });
    this.domain = this.pool.addDomain('Domain', { cognitoDomain: { domainPrefix: `warranty-claims-${props.stage}` } });

    for (const group of ['DEALER', 'ADJUSTER', 'FINANCE', 'OPS', 'GROUP_NORTH', 'GROUP_SOUTH', 'GROUP_WEST']) {
      new cognito.CfnUserPoolGroup(this, group, { userPoolId: this.pool.userPoolId, groupName: group });
    }

    this.webClient = this.pool.addClient('Web', {
      generateSecret: false,
      oAuth: {
        flows: { authorizationCodeGrant: true },
        scopes: [cognito.OAuthScope.OPENID],
        callbackUrls: [`https://${props.domainName}/signed-in`],
        logoutUrls: [`https://${props.domainName}/`],
      },
      preventUserExistenceErrors: true,
    });

    const scopes: Record<string, [cognito.UserPoolResourceServer, cognito.ResourceServerScope]> = {};
    const resource = (name: string, actions: string[]) => {
      const list = actions.map((scopeName) => new cognito.ResourceServerScope({ scopeName, scopeDescription: `${name} ${scopeName}` }));
      const server = this.pool.addResourceServer(`${name}-api`, { identifier: name, scopes: list });
      list.forEach((s) => (scopes[`${name}/${s.scopeName}`] = [server, s]));
    };
    resource('claims', ['read']);
    resource('groups', ['move']);
    resource('pricing', ['quote', 'compare']);
    resource('assistant', ['evaluate']);

    const machine = (name: Machine) =>
      this.pool.addClient(`${name}-machine`, {
        generateSecret: true,
        oAuth: {
          flows: { clientCredentials: true },
          scopes: MACHINE_SCOPES[name].map((s) => cognito.OAuthScope.resourceServer(...scopes[s]!)),
        },
      });
    this.machines = {
      claims: machine('claims'),
      assistant: machine('assistant'),
      migration: machine('migration'),
      evaluation: machine('evaluation'),
    };
  }

  get issuer() {
    return `https://cognito-idp.${this.region}.amazonaws.com/${this.pool.userPoolId}`;
  }

  get tokenUrl() {
    return `${this.domain.baseUrl()}/oauth2/token`;
  }
}
