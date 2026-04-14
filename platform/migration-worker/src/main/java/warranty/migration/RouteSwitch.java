package warranty.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;

/** Points a dealer group's traffic at one system. */
interface RouteSwitch {
    void toPlatform(String group);

    void toLegacy(String group);

    record Alb(ElasticLoadBalancingV2Client elb, RouteProperties routes) implements RouteSwitch {
        @Override
        public void toPlatform(String group) {
            point(group, routes.platformTargetGroup());
        }

        @Override
        public void toLegacy(String group) {
            point(group, routes.legacyTargetGroup());
        }

        private void point(String group, String target) {
            elb.modifyRule(r -> r.ruleArn(routes.ruleFor(group))
                    .actions(Action.builder().type(ActionTypeEnum.FORWARD).targetGroupArn(target).build()));
        }
    }

    /** On a laptop there is no load balancer. The owner column alone decides. */
    class LogOnly implements RouteSwitch {
        private static final Logger log = LoggerFactory.getLogger(RouteSwitch.class);

        @Override
        public void toPlatform(String group) {
            log.info("Route {} to the platform", group);
        }

        @Override
        public void toLegacy(String group) {
            log.info("Route {} to the old system", group);
        }
    }
}
