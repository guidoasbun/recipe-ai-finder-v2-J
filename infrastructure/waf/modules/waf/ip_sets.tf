# WAF IP Sets
#
# This file will contain:
# - aws_wafv2_ip_set for IPv4 allow-list
# - aws_wafv2_ip_set for IPv6 allow-list
# - aws_wafv2_ip_set for IPv4 block-list
# - aws_wafv2_ip_set for IPv6 block-list
#
# IP sets are standalone resources with no lifecycle dependency on the Web ACL,
# allowing independent updates without replacing the ACL.
