import { type App, type StackProps, Tags } from 'aws-cdk-lib';
import { AiStack } from './ai.js';
import { AuthStack } from './auth.js';
import { DataStack } from './data.js';
import { DeployRoleStack } from './deploy-role.js';
import { NetworkStack } from './network.js';
import { ServicesStack } from './services.js';
import { WebStack } from './web.js';

/** One call builds every stack for one stage: `cdk deploy --all -c stage=test`. */
export function build(app: App) {
  const stage = (app.node.tryGetContext('stage') as string | undefined) ?? 'test';
  const context = (key: string) => app.node.tryGetContext(key) as string;
  const props: StackProps = { env: { account: process.env['CDK_DEFAULT_ACCOUNT'], region: process.env['CDK_DEFAULT_REGION'] ?? 'us-east-1' } };
  const name = (part: string) => `Warranty-${stage}-${part}`;
  const production = stage === 'prod';
  const bedrockModel = context('bedrockModel');
  if (production && !bedrockModel) throw new Error('Set the full Bedrock model id with -c bedrockModel=...');

  const network = new NetworkStack(app, name('Network'), props);
  const auth = new AuthStack(app, name('Auth'), { ...props, stage, domainName: context('domainName') });
  const data = new DataStack(app, name('Data'), { ...props, vpc: network.vpc, production });
  const ai = new AiStack(app, name('Ai'), { ...props, stage, vpc: network.vpc });
  const services = new ServicesStack(app, name('Services'), {
    ...props,
    vpc: network.vpc,
    auth,
    data,
    ai,
    production,
    imageTag: context('imageTag'),
    bedrockModel,
    dealerGroups: app.node.tryGetContext('dealerGroups') as string[],
  });
  const web = new WebStack(app, name('Web'), { ...props, balancer: services.balancer });
  const role = new DeployRoleStack(app, name('DeployRole'), { ...props, repo: context('githubRepo') });

  Tags.of(app).add('app', 'warranty-claims');
  Tags.of(app).add('stage', stage);
  return { network, auth, data, ai, services, web, role };
}
