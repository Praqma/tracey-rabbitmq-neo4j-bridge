/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.praqma.tracey.tracey_rabbitmq_neo4j_bridge;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.QueueingConsumer;

import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author sofus
 */
public class AMQPFacade {

    ConnectionFactory connfac;
    Connection conn;
    Channel chan;
    Properties prop;

    public AMQPFacade(Properties prop) throws IOException {
        this.prop = prop;
        connfac = new ConnectionFactory();
        connfac.setHost(prop.getProperty("server_host"));
        connfac.setPort(Integer.parseInt(prop.getProperty("server_port", "5672")));
        connfac.setUsername(prop.getProperty("username"));
        connfac.setPassword(prop.getProperty("password"));
        conn = connfac.newConnection();
        chan = conn.createChannel();
        chan.exchangeDeclare(prop.getProperty("exchange_name"), prop.getProperty("exchange_type"), true);
    }

    public void recieveEvents(Tracey2Neo facade) throws IOException {
        String queue = chan.queueDeclare().getQueue();
        chan.queueBind(queue, prop.getProperty("exchange_name"), prop.getProperty("routing_key"));

        System.out.println(" [*] Waiting for messages. To exit press CTRL+C");
        chan.basicQos(10); // Only hold one message at a time
        QueueingConsumer consumer = new QueueingConsumer(chan);
        chan.basicConsume(queue, true, consumer);

        while (true) {
            try {
                System.out.println("  [x] Querying for new messages");
                QueueingConsumer.Delivery delivery = consumer.nextDelivery();
                String message = new String(delivery.getBody());
                System.out.println(" [x] Received '" + message + "'");
                facade.persistEvent(message);
                System.out.println(" [x] Done");
            } catch (Exception ex) {
                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    public void close(){
    	try {
			chan.close();
			conn.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	
    }
}
