package com.example.notificationforwarder;

import java.util.function.Consumer;

/**
 * An interface for sending messages to various platforms.
 * This defines a common contract that all message-sending classes must follow,
 * allowing for a unified approach regardless of the underlying service.
 */
public interface MessageHelper {

    /**
     * Sends a message to a channel using the provided token. The operation is
     * non-blocking and the result is returned via a callback.
     *
     * @param webToken   The unique token required for authentication with the service.
     * @param title      The main title of the message.
     * @param text       The body of the message.
     * @param senderInfo Additional information about the sender.
     * @param callback   A callback function that is invoked with a boolean
     * indicating the success (true) or failure (false) of the operation.
     */
    default void sendMessage(String webToken, String title, String text, String senderInfo, Consumer<Boolean> callback) {
        System.out.println("Default dummy implementation called !! call an actual helper here !!!");
    }
}

