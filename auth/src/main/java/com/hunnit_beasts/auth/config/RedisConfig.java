package com.hunnit_beasts.auth.config;

import com.hunnit_beasts.auth.core.redis.KillSwitchSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisConfig {

    public static final String KILLSWITCH_TOPIC = "doro.iam.killswitch";

    @Bean
    public ChannelTopic killSwitchTopic() {
        return new ChannelTopic(KILLSWITCH_TOPIC);
    }

    @Bean
    @Profile("!test")
    public MessageListenerAdapter messageListenerAdapter(KillSwitchSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber, "onMessage");
    }

    @Bean
    @Profile("!test")
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter messageListenerAdapter,
            ChannelTopic killSwitchTopic) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(messageListenerAdapter, killSwitchTopic);
        return container;
    }
}
