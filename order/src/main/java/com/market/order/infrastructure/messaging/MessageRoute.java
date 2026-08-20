package com.market.order.infrastructure.messaging;

import java.util.regex.Pattern;

record MessageRoute(String destinationTopic) {

    private static final Pattern TOPIC_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,249}");

    MessageRoute {
        if (destinationTopic == null || !TOPIC_PATTERN.matcher(destinationTopic).matches()) {
            throw new IllegalArgumentException("Destination topic has an invalid format");
        }
    }
}
