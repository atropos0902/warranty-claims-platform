import { Duration, Stack, type StackProps } from 'aws-cdk-lib';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elb from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as secrets from 'aws-cdk-lib/aws-secretsmanager';
import type { Construct } from 'constructs';
import type { AiStack } from './ai.js';
import { type AuthStack, MACHINE_SCOPES, type Machine } from './auth.js';
import type { DataStack } from './data.js';

type Props = StackProps & {
  vpc: ec2.IVpc;
  auth: AuthStack;
  data: DataStack;
  ai: AiStack;
  production: boolean;
  imageTag: string;
  bedrockModel: string;
  dealerGroups: string[];
};

type ServiceSpec = {
  name: string;
  paths: string[];
  priority: number;
  machine?: Machine;
  schema?: 'claims' | 'pricing' | 'assistant';
  taskRole?: iam.Role;
  env?: Record<string, string>;
  secrets?: Record<string, ecs.Secret>;
  memory?: number;
  single?: boolean;
  extraGroups?: ec2.ISecurityGroup[];
};

export class ServicesStack extends Stack {
  readonly balancer: elb.ApplicationLoadBalancer;
  readonly roles: Record<string, iam.IRole> = {};
  private readonly cluster: ecs.Cluster;
  private readonly listener: elb.ApplicationListener;
  private readonly taskGroup: ec2.SecurityGroup;

  constructor(
    scope: Construct,
    id: string,
    private readonly props: Props,
  ) {
    super(scope, id, props);
    const { vpc, data, ai } = props;
    this.cluster = new ecs.Cluster(this, 'Cluster', {
      vpc,
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
      defaultCloudMapNamespace: { name: 'warranty.local', useForServiceConnect: true },
    });
    this.taskGroup = new ec2.SecurityGroup(this, 'Tasks', { vpc });
    this.taskGroup.addIngressRule(this.taskGroup, ec2.Port.tcpRange(8080, 8090), 'between services');
    new ec2.CfnSecurityGroupIngress(this, 'MysqlFromTasks', {
      groupId: data.database.connections.securityGroups[0]!.securityGroupId,
      sourceSecurityGroupId: this.taskGroup.securityGroupId,
      ipProtocol: 'tcp',
      fromPort: 3306,
      toPort: 3306,
    });

    // Private. CloudFront reaches it through a VPC origin, so nothing on the internet can go around the CDN.
    this.balancer = new elb.ApplicationLoadBalancer(this, 'Balancer', { vpc, internetFacing: false, dropInvalidHeaderFields: true });
    this.listener = this.balancer.addListener('Http', {
      port: 80,
      open: false,
      defaultAction: elb.ListenerAction.fixedResponse(404, { contentType: 'text/plain', messageBody: 'Not found' }),
    });
    this.balancer.connections.allowFrom(
      ec2.Peer.prefixList(this.node.tryGetContext('cloudFrontPrefixList') as string),
      ec2.Port.tcp(80),
      'CloudFront',
    );

    const url = (service: string) => `http://${service}:8080`;
    const common = { CLAIMS_URL: url('claims'), PRICING_URL: url('pricing') };

    this.service({ name: 'pricing', paths: ['/api/v1/pricing/*'], priority: 10, schema: 'pricing' });
    const claims = this.service({
      name: 'claims',
      paths: ['/api/v1/claims', '/api/v1/claims/*', '/api/v1/dealer-groups', '/api/v1/dealer-groups/*'],
      priority: 20,
      machine: 'claims',
      schema: 'claims',
      env: { ...common, REPLAY_QUEUE_URL: data.replay.queueUrl, COMPARE_QUEUE_URL: data.compare.queueUrl },
    });
    data.replay.grantSendMessages(claims.taskDefinition.taskRole);
    data.compare.grantSendMessages(claims.taskDefinition.taskRole);

    this.reranker();
    this.service({
      name: 'assistant',
      paths: ['/api/v1/assistant/*'],
      priority: 30,
      machine: 'assistant',
      schema: 'assistant',
      taskRole: ai.assistantRole,
      memory: 2048,
      env: {
        ...common,
        CHAT_MODEL: 'bedrock-converse',
        BEDROCK_MODEL: props.bedrockModel,
        EMBEDDING_MODEL: 'bedrock-titan',
        OPENSEARCH_URL: ai.collection.attrCollectionEndpoint,
        RERANKER_URL: url('reranker'),
      },
      extraGroups: [ec2.SecurityGroup.fromSecurityGroupId(this, 'SearchClients', ai.searchClients.securityGroupId, { mutable: false })],
    });

    const platform = this.webGateway();
    const legacy = new elb.ApplicationTargetGroup(this, 'Legacy', {
      vpc,
      port: 8080,
      protocol: elb.ApplicationProtocol.HTTP,
      targetType: elb.TargetType.IP,
      healthCheck: { path: '/claims.action' },
    });
    const rules = props.dealerGroups.map((group, i) => {
      // Starts on the old system. The migration worker changes this action, and a later deploy leaves
      // it alone because the template has not changed.
      const rule = new elb.ApplicationListenerRule(this, `Route${group}`, {
        listener: this.listener,
        priority: 100 + i,
        conditions: [elb.ListenerCondition.httpHeader('Cookie', [`*dealer_group=${group}*`])],
        action: elb.ListenerAction.forward([legacy]),
      });
      return { group, arn: rule.listenerRuleArn };
    });

    const migration = this.service({
      name: 'migration',
      paths: ['/api/v1/moves', '/api/v1/moves/*'],
      priority: 40,
      machine: 'migration',
      single: true,
      env: {
        ...common,
        REPLAY_QUEUE_URL: data.replay.queueUrl,
        COMPARE_QUEUE_URL: data.compare.queueUrl,
        ROUTE_LISTENER_RULES: rules.map((r) => `${r.group}=${r.arn}`).join(','),
        LEGACY_TARGET_GROUP: legacy.targetGroupArn,
        PLATFORM_TARGET_GROUP: platform.targetGroupArn,
      },
      secrets: {
        LEGACY_DB_URL: ecs.Secret.fromSecretsManager(data.legacySecret, 'jdbcUrl'),
        LEGACY_DB_USER: ecs.Secret.fromSecretsManager(data.legacySecret, 'username'),
        LEGACY_DB_PASSWORD: ecs.Secret.fromSecretsManager(data.legacySecret, 'password'),
      },
    });
    data.replay.grantConsumeMessages(migration.taskDefinition.taskRole);
    data.compare.grantConsumeMessages(migration.taskDefinition.taskRole);
    migration.taskDefinition.addToTaskRolePolicy(
      new iam.PolicyStatement({ actions: ['elasticloadbalancing:ModifyRule'], resources: rules.map((r) => r.arn) }),
    );

    for (const [name, q] of Object.entries({ Replay: data.replay, Compare: data.compare })) {
      new cloudwatch.Alarm(this, `${name}Dead`, {
        metric: q.deadLetterQueue!.queue.metricApproximateNumberOfMessagesVisible({ period: Duration.minutes(1) }),
        threshold: 1,
        evaluationPeriods: 1,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        alarmDescription: `A ${name.toLowerCase()} message failed five times. See docs/team/runbook.md.`,
      });
    }
    new cloudwatch.Alarm(this, 'ReplayLate', {
      metric: data.replay.metricApproximateAgeOfOldestMessage({ period: Duration.minutes(1) }),
      threshold: 120,
      evaluationPeriods: 3,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      alarmDescription: 'Oracle is two minutes behind. Moving a group back would be slow.',
    });
  }

  /** A Spring Boot service with two target groups. ECS shifts traffic, waits, and goes back when the alarm fires. */
  private service(spec: ServiceSpec): ecs.FargateService {
    const { vpc, data, auth, production, imageTag } = this.props;
    const task = new ecs.FargateTaskDefinition(this, `${spec.name}Task`, {
      cpu: 512,
      memoryLimitMiB: spec.memory ?? 1024,
      runtimePlatform: { cpuArchitecture: ecs.CpuArchitecture.X86_64, operatingSystemFamily: ecs.OperatingSystemFamily.LINUX },
      ...(spec.taskRole ? { taskRole: spec.taskRole } : {}),
    });
    this.roles[spec.name] = task.taskRole;

    const env: Record<string, string> = {
      PORT: '8080',
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: auth.issuer,
      JAVA_TOOL_OPTIONS: '-XX:MaxRAMPercentage=50',
      TRACING_ENABLED: 'true',
      ...spec.env,
    };
    const hidden: Record<string, ecs.Secret> = { ...spec.secrets };
    if (spec.machine) {
      const client = auth.machines[spec.machine];
      env['WARRANTY_AUTH_TOKEN_URL'] = auth.tokenUrl;
      env['WARRANTY_AUTH_CLIENT_ID'] = client.userPoolClientId;
      env['WARRANTY_AUTH_SCOPES'] = MACHINE_SCOPES[spec.machine].join(' ');
      const stored = new secrets.Secret(this, `${spec.name}ClientSecret`, { secretStringValue: client.userPoolClientSecret });
      hidden['WARRANTY_AUTH_CLIENT_SECRET'] = ecs.Secret.fromSecretsManager(stored);
    }
    if (spec.schema) {
      const prefix = spec.schema.toUpperCase();
      env[`${prefix}_DB_URL`] = `jdbc:mysql://${data.database.dbInstanceEndpointAddress}:3306/${spec.schema}?sslMode=REQUIRED`;
      env[`${prefix}_DB_USER`] = spec.schema;
      hidden[`${prefix}_DB_PASSWORD`] = ecs.Secret.fromSecretsManager(data.users[spec.schema], 'password');
    }
    task.addContainer('app', {
      image: ecs.ContainerImage.fromEcrRepository(ecr.Repository.fromRepositoryName(this, `${spec.name}Repo`, `warranty/${spec.name}`), imageTag),
      portMappings: [{ name: 'http', containerPort: 8080 }],
      environment: env,
      secrets: hidden,
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: spec.name, logRetention: logs.RetentionDays.ONE_MONTH }),
      readonlyRootFilesystem: false,
    });

    task.addContainer('otel', {
      image: ecs.ContainerImage.fromRegistry('public.ecr.aws/aws-observability/aws-otel-collector:v0.43.3'),
      essential: false,
      command: ['--config=/etc/ecs/ecs-default-config.yaml'],
      memoryReservationMiB: 64,
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'otel', logRetention: logs.RetentionDays.ONE_WEEK }),
    });
    task.addToTaskRolePolicy(
      new iam.PolicyStatement({ actions: ['xray:PutTraceSegments', 'xray:PutTelemetryRecords'], resources: ['*'] }),
    );

    const group = (color: string) =>
      new elb.ApplicationTargetGroup(this, `${spec.name}${color}`, {
        vpc,
        port: 8080,
        protocol: elb.ApplicationProtocol.HTTP,
        targetType: elb.TargetType.IP,
        deregistrationDelay: Duration.seconds(20),
        healthCheck: { path: '/actuator/health/readiness', interval: Duration.seconds(10) },
      });
    const blue = group('Blue');
    const green = group('Green');
    const rule = new elb.ApplicationListenerRule(this, `${spec.name}Rule`, {
      listener: this.listener,
      priority: spec.priority,
      conditions: [elb.ListenerCondition.pathPatterns(spec.paths)],
      action: elb.ListenerAction.weightedForward([
        { targetGroup: blue, weight: 100 },
        { targetGroup: green, weight: 0 },
      ]),
    });

    const errors = new cloudwatch.Alarm(this, `${spec.name}Errors`, {
      metric: new cloudwatch.MathExpression({
        expression: 'FILL(blue, 0) + FILL(green, 0)',
        period: Duration.minutes(1),
        usingMetrics: {
          blue: blue.metrics.httpCodeTarget(elb.HttpCodeTarget.TARGET_5XX_COUNT, { statistic: 'Sum' }),
          green: green.metrics.httpCodeTarget(elb.HttpCodeTarget.TARGET_5XX_COUNT, { statistic: 'Sum' }),
        },
      }),
      threshold: 5,
      evaluationPeriods: 2,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    const service = new ecs.FargateService(this, spec.name, {
      cluster: this.cluster,
      taskDefinition: task,
      desiredCount: spec.single ? 1 : production ? 3 : 1,
      securityGroups: [this.taskGroup, ...(spec.extraGroups ?? [])],
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
      serviceConnectConfiguration: { services: [{ portMappingName: 'http', dnsName: spec.name, port: 8080 }] },
      deploymentAlarms: { alarmNames: [errors.alarmName], behavior: ecs.AlarmBehavior.ROLLBACK_ON_ALARM },
    });
    service.attachToApplicationTargetGroup(blue);
    service.node.addDependency(rule);

    const shift = new iam.Role(this, `${spec.name}Shift`, {
      assumedBy: new iam.ServicePrincipal('ecs.amazonaws.com'),
      managedPolicies: [iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonECSInfrastructureRolePolicyForLoadBalancers')],
    });
    const raw = service.node.defaultChild as ecs.CfnService;
    raw.addPropertyOverride('DeploymentConfiguration.Strategy', 'BLUE_GREEN');
    raw.addPropertyOverride('DeploymentConfiguration.BakeTimeInMinutes', 10);
    raw.addPropertyOverride('LoadBalancers.0.AdvancedConfiguration', {
      AlternateTargetGroupArn: green.targetGroupArn,
      ProductionListenerRule: rule.listenerRuleArn,
      RoleArn: shift.roleArn,
    });
    this.taskGroup.connections.allowFrom(this.balancer, ec2.Port.tcp(8080));
    return service;
  }

  /** Serves index.html. Assets come from S3 through CloudFront. Its target group is where a moved dealer group is sent. */
  private webGateway(): elb.ApplicationTargetGroup {
    const task = new ecs.FargateTaskDefinition(this, 'webTask', { cpu: 256, memoryLimitMiB: 512 });
    task.addContainer('web', {
      image: ecs.ContainerImage.fromEcrRepository(ecr.Repository.fromRepositoryName(this, 'webRepo', 'warranty/web'), this.props.imageTag),
      portMappings: [{ containerPort: 8080 }],
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'web', logRetention: logs.RetentionDays.ONE_MONTH }),
    });
    const service = new ecs.FargateService(this, 'web', {
      cluster: this.cluster,
      taskDefinition: task,
      desiredCount: this.props.production ? 2 : 1,
      minHealthyPercent: 100,
      securityGroups: [this.taskGroup],
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      circuitBreaker: { rollback: true },
    });
    const group = new elb.ApplicationTargetGroup(this, 'WebTarget', {
      vpc: this.props.vpc,
      port: 8080,
      protocol: elb.ApplicationProtocol.HTTP,
      targets: [service],
      healthCheck: { path: '/healthz' },
    });
    new elb.ApplicationListenerRule(this, 'WebRule', {
      listener: this.listener,
      priority: 900,
      conditions: [elb.ListenerCondition.pathPatterns(['/*'])],
      action: elb.ListenerAction.forward([group]),
    });
    return group;
  }

  /** bge-reranker-v2-m3 on Text Embeddings Inference. Not on the load balancer: only the assistant calls it. */
  private reranker() {
    const task = new ecs.FargateTaskDefinition(this, 'rerankerTask', { cpu: 4096, memoryLimitMiB: 8192 });
    task.addContainer('tei', {
      image: ecs.ContainerImage.fromRegistry('ghcr.io/huggingface/text-embeddings-inference:cpu-1.8'),
      command: ['--model-id', 'BAAI/bge-reranker-v2-m3', '--port', '8080'],
      portMappings: [{ name: 'http', containerPort: 8080 }],
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'reranker', logRetention: logs.RetentionDays.ONE_MONTH }),
    });
    new ecs.FargateService(this, 'reranker', {
      cluster: this.cluster,
      taskDefinition: task,
      desiredCount: 1,
      minHealthyPercent: 100,
      securityGroups: [this.taskGroup],
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      serviceConnectConfiguration: { services: [{ portMappingName: 'http', dnsName: 'reranker', port: 8080 }] },
      circuitBreaker: { rollback: true },
    });
  }
}
