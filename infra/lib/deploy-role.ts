import { Duration, Stack, type StackProps } from 'aws-cdk-lib';
import * as iam from 'aws-cdk-lib/aws-iam';
import type { Construct } from 'constructs';

/** The role GitHub Actions takes through OIDC. Only the main branch of one repository can take it. */
export class DeployRoleStack extends Stack {
  constructor(scope: Construct, id: string, props: StackProps & { repo: string }) {
    super(scope, id, props);
    const github = new iam.OpenIdConnectProvider(this, 'GitHub', {
      url: 'https://token.actions.githubusercontent.com',
      clientIds: ['sts.amazonaws.com'],
    });
    const role = new iam.Role(this, 'Deploy', {
      maxSessionDuration: Duration.hours(1),
      assumedBy: new iam.WebIdentityPrincipal(github.openIdConnectProviderArn, {
        StringEquals: {
          'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
          'token.actions.githubusercontent.com:sub': `repo:${props.repo}:ref:refs/heads/main`,
        },
      }),
    });
    role.addToPolicy(
      new iam.PolicyStatement({ actions: ['sts:AssumeRole'], resources: [`arn:aws:iam::${this.account}:role/cdk-*`] }),
    );
    role.addToPolicy(new iam.PolicyStatement({ actions: ['ecr:GetAuthorizationToken'], resources: ['*'] }));
    role.addToPolicy(
      new iam.PolicyStatement({
        actions: ['ecr:BatchCheckLayerAvailability', 'ecr:InitiateLayerUpload', 'ecr:UploadLayerPart', 'ecr:CompleteLayerUpload', 'ecr:PutImage', 'ecr:BatchGetImage', 'ecr:GetDownloadUrlForLayer'],
        resources: [`arn:aws:ecr:${this.region}:${this.account}:repository/warranty/*`],
      }),
    );
  }
}
