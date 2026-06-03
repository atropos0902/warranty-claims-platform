import { App } from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';
import { build } from '../lib/build.js';

const context = JSON.parse(readFileSync(new URL('../cdk.json', import.meta.url), 'utf8')).context;
let t: Record<'network' | 'auth' | 'data' | 'ai' | 'services' | 'web' | 'role', Template>;

beforeAll(() => {
  const stacks = build(new App({ context: { ...context, stage: 'prod', bedrockModel: 'model-id' } }));
  t = Object.fromEntries(Object.entries(stacks).map(([k, s]) => [k, Template.fromStack(s)])) as typeof t;
}, 60_000);

const policies = (template: Template, like: string) =>
  Object.entries(template.findResources('AWS::IAM::Policy'))
    .filter(([id]) => id.toLowerCase().startsWith(like))
    .flatMap(([, p]) => p['Properties'].PolicyDocument.Statement as { Action: string | string[]; Resource: unknown }[]);
const actions = (s: { Action: string | string[] }[]) => s.flatMap((x) => x.Action);

describe('the assistant', () => {
  it('can call two models and one collection, send traces, and nothing else', () => {
    const own = policies(t.ai, 'assistanttask').filter((s) => !JSON.stringify(s.Action).includes('xray:'));
    expect(new Set(actions(own))).toEqual(new Set(['aoss:APIAccessAll', 'bedrock:InvokeModel']));
    expect(JSON.stringify(own)).not.toContain('"Resource":"*"');
  });

  it('signs in with read scopes only', () => {
    t.auth.hasResourceProperties('AWS::Cognito::UserPoolClient', {
      AllowedOAuthScopes: Match.arrayEquals([
        { 'Fn::Join': ['', [Match.anyValue(), '/read']] },
        { 'Fn::Join': ['', [Match.anyValue(), '/quote']] },
      ]),
      AllowedOAuthFlows: ['client_credentials'],
    });
  });

  it('keeps the clause index off the public network', () => {
    const network = Object.values(t.ai.findResources('AWS::OpenSearchServerless::SecurityPolicy')).find((r) => r['Properties'].Type === 'network');
    expect(JSON.stringify(network)).toContain('\\"AllowFromPublic\\":false');
  });
});

describe('services', () => {
  it('deploy blue/green and go back when the alarm fires', () => {
    const all = Object.values(t.services.findResources('AWS::ECS::Service')).map((s) => s['Properties']);
    const shifted = all.filter((p) => p.DeploymentConfiguration?.Strategy === 'BLUE_GREEN');
    expect(shifted).toHaveLength(4);
    for (const p of shifted) {
      expect(p.DeploymentConfiguration.Alarms).toMatchObject({ Enable: true, Rollback: true });
      expect(p.LoadBalancers[0].AdvancedConfiguration.AlternateTargetGroupArn).toBeDefined();
    }
  });

  it('only let the migration worker change routes, and only those rules', () => {
    const everyone = Object.values(t.services.findResources('AWS::IAM::Policy')).filter((p) => JSON.stringify(p).includes('elasticloadbalancing:ModifyRule'));
    expect(everyone).toHaveLength(1);
    const statement = everyone[0]!['Properties'].PolicyDocument.Statement.find((s: { Action: string }) => s.Action === 'elasticloadbalancing:ModifyRule');
    expect(statement.Resource).toHaveLength(3);
    expect(JSON.stringify(statement.Resource)).not.toContain('*');
  });

  it('start every dealer group on the old system', () => {
    const rules = Object.values(t.services.findResources('AWS::ElasticLoadBalancingV2::ListenerRule')).map((r) => r['Properties']);
    const routed = rules.filter((r) => JSON.stringify(r.Conditions).includes('dealer_group='));
    expect(routed).toHaveLength(3);
    routed.forEach((r) => expect(JSON.stringify(r.Actions)).toContain('Legacy'));
  });

  it('answer only to CloudFront', () => {
    t.services.hasResourceProperties('AWS::ElasticLoadBalancingV2::LoadBalancer', { Scheme: 'internal' });
    t.web.resourceCountIs('AWS::CloudFront::VpcOrigin', 1);
    expect(JSON.stringify(t.services.toJSON())).not.toContain('0.0.0.0/0","Description":"Allow from anyone');
  });

  it('never put a password in plain environment', () => {
    for (const d of Object.values(t.services.findResources('AWS::ECS::TaskDefinition'))) {
      for (const c of d['Properties'].ContainerDefinitions) {
        for (const e of c.Environment ?? []) expect(e.Name).not.toMatch(/PASSWORD|CLIENT_SECRET|API_KEY/);
      }
    }
  });
});

describe('data', () => {
  it('is encrypted, private and protected', () => {
    t.data.hasResourceProperties('AWS::RDS::DBInstance', { StorageEncrypted: true, PubliclyAccessible: false, DeletionProtection: true, MultiAZ: true, EngineVersion: '8.4' });
  });

  it('gives each queue a dead letter queue and keeps order', () => {
    const queues = Object.values(t.data.findResources('AWS::SQS::Queue')).map((q) => q['Properties']);
    expect(queues.every((q) => q.FifoQueue)).toBe(true);
    expect(queues.filter((q) => q.RedrivePolicy?.maxReceiveCount === 5)).toHaveLength(2);
  });

  it('copies the two claim tables and follows changes', () => {
    t.data.hasResourceProperties('AWS::DMS::ReplicationTask', { MigrationType: 'full-load-and-cdc' });
  });
});

describe('access', () => {
  it('has no long-lived keys anywhere', () => {
    for (const template of Object.values(t)) {
      template.resourceCountIs('AWS::IAM::User', 0);
      template.resourceCountIs('AWS::IAM::AccessKey', 0);
    }
  });

  it('lets only the main branch of one repository deploy', () => {
    t.role.hasResourceProperties('AWS::IAM::Role', {
      AssumeRolePolicyDocument: Match.objectLike({
        Statement: [Match.objectLike({ Condition: { StringEquals: Match.objectLike({ 'token.actions.githubusercontent.com:sub': Match.stringLikeRegexp('^repo:.+:ref:refs/heads/main$') }) } })],
      }),
    });
  });

  it('asks people for a second factor', () => {
    t.auth.hasResourceProperties('AWS::Cognito::UserPool', { MfaConfiguration: 'ON' });
  });
});
