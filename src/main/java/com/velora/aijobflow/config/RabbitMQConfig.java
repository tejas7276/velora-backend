package com.velora.aijobflow.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// @Configuration
public class RabbitMQConfig {

    @Value("${queue.job.name}")
    private String queueName;

    @Value("${queue.job.exchange}")
    private String exchangeName;

    @Value("${queue.job.routing-key}")
    private String routingKey;

    @Bean
    public Queue jobQueue() {
        // x-max-priority enables priority queue — values 0-10
        // CRITICAL(10) > HIGH(8) > MEDIUM(5) > LOW(2)
        return QueueBuilder
                .durable(queueName)
                .withArgument("x-max-priority", 10)
                .build();
    }

    @Bean
    public TopicExchange jobExchange() {
        return new TopicExchange(exchangeName);
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
        return rabbitTemplate;
    }
}