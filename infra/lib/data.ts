import { Duration, RemovalPolicy, Stack, type StackProps } from 'aws-cdk-lib';
import * as dms from 'aws-cdk-lib/aws-dms';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as secrets from 'aws-cdk-lib/aws-secretsmanager';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import type { Construct } from 'constructs';

type Props = StackProps & { vpc: ec2.IVpc; production: boolean };

export class DataStack extends Stack {
  readonly database: rds.DatabaseInstance;
  readonly users: Record<'claims' | 'pricing' | 'assistant' | 'dms', rds.DatabaseSecret>;
  readonly legacySecret: secrets.Secret;
  readonly replay: sqs.Queue;
  readonly compare: sqs.Queue;

  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);

    const engine = rds.DatabaseInstanceEngine.mysql({ version: rds.MysqlEngineVersion.of('8.4', '8.4') });
    this.database = new rds.DatabaseInstance(this, 'Mysql', {
      engine,
      vpc: props.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MEDIUM),
      multiAz: props.production,
      storageEncrypted: true,
      publiclyAccessible: false,
      backupRetention: Duration.days(props.production ? 14 : 1),
      deletionProtection: props.production,
      removalPolicy: props.production ? RemovalPolicy.RETAIN : RemovalPolicy.SNAPSHOT,
      cloudwatchLogsExports: ['error', 'slowquery'],
      // The staging triggers need this while binary logging is on.
      parameterGroup: new rds.ParameterGroup(this, 'Params', {
        engine,
        parameters: { log_bin_trust_function_creators: '1', require_secure_transport: 'ON', slow_query_log: '1' },
      }),
      credentials: rds.Credentials.fromGeneratedSecret('admin'),
    });

    const user = (username: string) =>
      new rds.DatabaseSecret(this, `${username}-user`, { username, masterSecret: this.database.secret! });
    this.users = { claims: user('claims'), pricing: user('pricing'), assistant: user('assistant'), dms: user('dms') };

    // Filled in by hand once: host, port, sid, username and password of the old Oracle database.
    this.legacySecret = new secrets.Secret(this, 'LegacyOracle', { description: 'Connection to the old claims database' });

    const queue = (name: string) =>
      new sqs.Queue(this, name, {
        fifo: true,
        visibilityTimeout: Duration.seconds(60),
        enforceSSL: true,
        encryption: sqs.QueueEncryption.SQS_MANAGED,
        deadLetterQueue: {
          maxReceiveCount: 5,
          queue: new sqs.Queue(this, `${name}Dead`, {
            fifo: true,
            enforceSSL: true,
            encryption: sqs.QueueEncryption.SQS_MANAGED,
            retentionPeriod: Duration.days(14),
          }),
        },
      });
    this.replay = queue('Replay');
    this.compare = queue('Compare');

    this.copyFromOracle(props.vpc);
  }

  /** Full load, then change capture, from the Oracle CLAIMS schema into MySQL staging. */
  private copyFromOracle(vpc: ec2.IVpc) {
    const role = new iam.Role(this, 'DmsSecrets', { assumedBy: new iam.ServicePrincipal(`dms.${this.region}.amazonaws.com`) });
    this.legacySecret.grantRead(role);
    this.users.dms.grantRead(role);

    const group = new ec2.SecurityGroup(this, 'DmsGroup', { vpc });
    this.database.connections.allowDefaultPortFrom(group);
    const subnets = new dms.CfnReplicationSubnetGroup(this, 'DmsSubnets', {
      replicationSubnetGroupDescription: 'Application subnets',
      subnetIds: vpc.selectSubnets({ subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS }).subnetIds,
    });
    const instance = new dms.CfnReplicationInstance(this, 'DmsInstance', {
      replicationInstanceClass: 'dms.t3.medium',
      allocatedStorage: 50,
      publiclyAccessible: false,
      replicationSubnetGroupIdentifier: subnets.ref,
      vpcSecurityGroupIds: [group.securityGroupId],
    });
    const source = new dms.CfnEndpoint(this, 'OracleSource', {
      endpointType: 'source',
      engineName: 'oracle',
      oracleSettings: { secretsManagerSecretId: this.legacySecret.secretArn, secretsManagerAccessRoleArn: role.roleArn },
    });
    const target = new dms.CfnEndpoint(this, 'StagingTarget', {
      endpointType: 'target',
      engineName: 'mysql',
      sslMode: 'require',
      // The header can land before its lines. The loader waits for both, so DMS need not check keys.
      extraConnectionAttributes: 'initstmt=SET FOREIGN_KEY_CHECKS=0;targetDbType=SPECIFIC_DATABASE',
      databaseName: 'staging',
      mySqlSettings: { secretsManagerSecretId: this.users.dms.secretArn, secretsManagerAccessRoleArn: role.roleArn },
    });
    new dms.CfnReplicationTask(this, 'Copy', {
      migrationType: 'full-load-and-cdc',
      replicationInstanceArn: instance.ref,
      sourceEndpointArn: source.ref,
      targetEndpointArn: target.ref,
      tableMappings: JSON.stringify(TABLE_MAPPINGS),
      replicationTaskSettings: JSON.stringify({
        TargetMetadata: { BatchApplyEnabled: false },
        FullLoadSettings: { TargetTablePrepMode: 'DO_NOTHING' },
        ErrorBehavior: { DataErrorPolicy: 'SUSPEND_TABLE', TableErrorPolicy: 'STOP_TASK' },
        Logging: { EnableLogging: true },
      }),
    });
  }
}

const table = (id: number, name: string) => ({
  'rule-type': 'selection',
  'rule-id': String(id),
  'rule-name': String(id),
  'object-locator': { 'schema-name': 'CLAIMS', 'table-name': name },
  'rule-action': 'include',
});
const change = (id: number, target: string, action: string, locator: object, value?: string) => ({
  'rule-type': 'transformation',
  'rule-id': String(id),
  'rule-name': String(id),
  'rule-target': target,
  'object-locator': locator,
  'rule-action': action,
  ...(value ? { value } : {}),
});
export const TABLE_MAPPINGS = {
  rules: [
    table(1, 'CLAIMS'),
    table(2, 'CLAIM_LINES'),
    change(3, 'schema', 'rename', { 'schema-name': 'CLAIMS' }, 'staging'),
    change(4, 'table', 'convert-lowercase', { 'schema-name': 'CLAIMS', 'table-name': '%' }),
    change(5, 'column', 'convert-lowercase', { 'schema-name': 'CLAIMS', 'table-name': '%', 'column-name': '%' }),
  ],
};
