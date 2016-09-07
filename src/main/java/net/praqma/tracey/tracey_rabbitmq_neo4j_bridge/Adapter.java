/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.praqma.tracey.tracey_rabbitmq_neo4j_bridge;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

/**
 *
 * @author sofus
 */
public class Adapter {

    public static void main(String[] args) throws IOException {
	ClassLoader classLoader = Adapter.class.getClassLoader();
    	
        File amqpFile = new File(classLoader.getResource("AMQP.properties").getFile());
        File neoFile = new File(classLoader.getResource("NEO4J.properties").getFile());
        Properties amqpprop = new Properties();
        amqpprop.load(new FileReader(amqpFile));
        Properties neoprop = new Properties();
        neoprop.load(new FileReader(neoFile));
        AMQPFacade amqp = new AMQPFacade(amqpprop);
        Tracey2Neo neo= new Tracey2Neo(neoprop);
        amqp.recieveEvents(neo);
    }
}
