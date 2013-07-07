package io.reign.examples;

import io.reign.Reign;
import io.reign.mesg.DefaultMessagingService;
import io.reign.mesg.ResponseMessage;
import io.reign.mesg.SimpleRequestMessage;
import io.reign.presence.PresenceService;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrate basic usage.
 * 
 * @author ypai
 * 
 */
public class MessagingServiceExample {

    private static final Logger logger = LoggerFactory.getLogger(MessagingServiceExample.class);

    public static void main(String[] args) throws Exception {
        /** init and start reign using builder **/
        Reign reign = Reign.maker().zkClient("localhost:2181", 30000).core().get();
        reign.start();

        /** init and start using Spring convenience builder **/
        // SpringReignMaker springReignMaker = new SpringReignMaker();
        // springReignMaker.setZkConnectString("localhost:2181");
        // springReignMaker.setZkSessionTimeout(30000);
        // springReignMaker.setCore(true);
        // springReignMaker.initializeAndStart();
        // Reign reign = springReignMaker.get();

        /** messaging example **/
        messagingExample(reign);

        /** sleep to allow examples to run for a bit **/
        Thread.sleep(600000);

        /** shutdown reign **/
        reign.stop();

        /** sleep a bit to observe observer callbacks **/
        Thread.sleep(10000);
    }

    public static void messagingExample(Reign reign) throws Exception {
        PresenceService presenceService = reign.getService("presence");
        presenceService.announce("examples", "service1", true);
        presenceService.announce("examples", "service2", true);

        presenceService.waitUntilAvailable("examples", "service1", 30000);

        Thread.sleep(5000);

        DefaultMessagingService messagingService = reign.getService("messaging");

        Map<String, ResponseMessage> responseMap = messagingService.sendMessage("examples", "service1",
                new SimpleRequestMessage("presence", "/"));

        logger.info("Broadcast#1:  responseMap={}", responseMap);

        responseMap = messagingService.sendMessage("examples", "service1", new SimpleRequestMessage("presence",
                "/examples/service1"));

        logger.info("Broadcast#2:  responseMap={}", responseMap);

        responseMap = messagingService.sendMessage("examples", "service1", new SimpleRequestMessage("presence",
                "/examples"));

        logger.info("Broadcast#3:  responseMap={}", responseMap);
    }
}