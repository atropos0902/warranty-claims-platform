import { Stack, type StackProps } from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as aoss from 'aws-cdk-lib/aws-opensearchserverless';
import type { Construct } from 'constructs';

type Props = StackProps & { stage: string; vpc: ec2.IVpc };

/** The vector collection that holds policy clauses. Reachable from the VPC only. */
export class AiStack extends Stack {
  readonly assistantRole: iam.Role;
  readonly collection: aoss.CfnCollection;
  readonly searchClients: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);
    const name = `policy-${props.stage}`;
    this.assistantRole = new iam.Role(this, 'AssistantTask', { assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com') });
    this.searchClients = new ec2.SecurityGroup(this, 'SearchClients', { vpc: props.vpc });
    const endpointGroup = new ec2.SecurityGroup(this, 'SearchEndpoint', { vpc: props.vpc });
    endpointGroup.addIngressRule(this.searchClients, ec2.Port.tcp(443));

    const endpoint = new aoss.CfnVpcEndpoint(this, 'Endpoint', {
      name,
      vpcId: props.vpc.vpcId,
      subnetIds: props.vpc.selectSubnets({ subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS }).subnetIds,
      securityGroupIds: [endpointGroup.securityGroupId],
    });
    const resource = [`collection/${name}`];
    const encryption = new aoss.CfnSecurityPolicy(this, 'Encryption', {
      name,
      type: 'encryption',
      policy: JSON.stringify({ Rules: [{ ResourceType: 'collection', Resource: resource }], AWSOwnedKey: true }),
    });
    const network = new aoss.CfnSecurityPolicy(this, 'Network', {
      name,
      type: 'network',
      policy: this.toJsonString([
        { Rules: [{ ResourceType: 'collection', Resource: resource }], AllowFromPublic: false, SourceVPCEs: [endpoint.attrId] },
      ]),
    });
    new aoss.CfnAccessPolicy(this, 'Access', {
      name,
      type: 'data',
      policy: this.toJsonString([
        {
          Principal: [this.assistantRole.roleArn],
          Rules: [
            {
              ResourceType: 'index',
              Resource: [`index/${name}/clauses-*`],
              Permission: ['aoss:CreateIndex', 'aoss:DescribeIndex', 'aoss:ReadDocument', 'aoss:WriteDocument'],
            },
          ],
        },
      ]),
    });
    this.collection = new aoss.CfnCollection(this, 'Collection', { name, type: 'VECTORSEARCH' });
    this.collection.addDependency(encryption);
    this.collection.addDependency(network);

    this.assistantRole.addToPolicy(new iam.PolicyStatement({ actions: ['aoss:APIAccessAll'], resources: [this.collection.attrArn] }));
    this.assistantRole.addToPolicy(
      new iam.PolicyStatement({
        actions: ['bedrock:InvokeModel'],
        resources: [
          'arn:aws:bedrock:*::foundation-model/anthropic.claude-sonnet-4*',
          'arn:aws:bedrock:*::foundation-model/amazon.titan-embed-text-v2:0',
          `arn:aws:bedrock:${this.region}:${this.account}:inference-profile/us.anthropic.claude-sonnet-4*`,
        ],
      }),
    );
  }
}
