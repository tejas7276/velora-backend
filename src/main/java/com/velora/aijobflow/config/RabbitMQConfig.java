package com.velora.aijobflow.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for CloudAMQP (amqps:// TLS connection).
 *
 * WHAT WAS WRONG:
 * 1. RabbitMQ host was commented out in application.properties →
 *    Spring defaulted to localhost:5671 with SSL → immediate connection
 *    refused on Render → startup delay + job dispatch failures.
 *
 * 2. Queue 'job-queue' existed on CloudAMQP with DIFFERENT args
 *    (previously created without x-max-priority=10) → Spring tried to
 *    redeclare with x-max-priority=10 → PRECONDITION_FAILED from broker
 *    → SimpleMessageListenerContainer spawned a new retry thread every 5s
 *    → 154+ leaked threads (tContainer#0-154 in logs).
 *    Queue was deleted from CloudAMQP dashboard → fresh declare now works.
 *
 * 3. RabbitTemplate.convertAndSend() was called directly inside
 *    @Transactional createJob() → AmqpException crashed the whole method
 *    → job was saved to DB but API returned 500 → UI showed failure.
 *
 * ALL THREE FIXED: correct host config + queue deleted + dispatchToQueue()
 * with try-catch in JobService means RabbitMQ failure never surfaces to user.
 *
 * CloudAMQP connection parameters (from dashboard):
 *   Host:     raccoon.lmq.cloudamqp.com
 *   Port:     5671 (TLS)
 *   Username: gsxvcbin
 *   Vhost:    gsxvcbin  (CloudAMQP always sets vhost = username)
 *   SSL:      required (port 5671 is TLS-only)
 */
@Configuration
public class RabbitMQConfig {

    @Value("${queue.job.name}")
    private String queueName;

    @Value("${queue.job.exchange}")
    private String exchangeName;

    @Value("${queue.job.routing-key}")
    private String routingKey;

    /**
     * Durable priority queue.
     * x-max-priority=10 → values 0-10, higher = processed first.
     * Durable → survives CloudAMQP restarts.
     *
     * IMPORTANT: If this queue was previously declared without x-max-priority
     * you MUST delete it from the CloudAMQP dashboard before deploying,
     * otherwise you'll get PRECONDITION_FAILED and the retry loop returns.
     */
    @Bean
    public Queue jobQueue() {
        return QueueBuilder
                .durable(queueName)
                .withArgument("x-max-priority", 10)
                .build();
    }

    @Bean
    public TopicExchange jobExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Binding binding(Queue jobQueue, TopicExchange jobExchange) {
        return BindingBuilder
                .bind(jobQueue)
                .to(jobExchange)
                .with(routingKey);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        // mandatory=false: if publish fails (no route), drop silently.
        // We catch AmqpException in JobService.dispatchToQueue() anyway.
        rabbitTemplate.setMandatory(false);
        return rabbitTemplate;
    }

    /**
     * Listener container factory with explicit error handling.
     * Even if a message fails processing, the container recovers
     * without crashing the whole Spring context.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        // How many messages a single consumer processes concurrently
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(3);
        // If message processing throws exception → nack + requeue=false
        // (prevents poison message infinite loop)
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}