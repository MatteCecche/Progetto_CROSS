package client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/*
 * Listener per notifiche multicast di soglie prezzo
 */
public class MulticastListener implements Runnable {

    private static final String SEPARATOR = "================================================================================";

    // Socket multicast per ricezione messaggi
    private MulticastSocket multicastSocket;

    // Indirizzo del gruppo come SocketAddress
    private InetSocketAddress groupSocketAddress;

    // Indirizzo e porta del gruppo multicast
    private final String multicastAddress;
    private final int multicastPort;

    // Username dell'utente per logging
    private final String username;

    // Flag per controllo esecuzione thread
    private volatile boolean running;

    // Colori per risposte
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_BOLD = "\u001B[1m";

    public MulticastListener(String multicastAddress, int multicastPort, String username) {
        this.multicastAddress = multicastAddress;
        this.multicastPort = multicastPort;
        this.username = username;
        this.running = false;
    }

    public void start() throws IOException {
        try {
            multicastSocket = new MulticastSocket(null);
            multicastSocket.setReuseAddress(true);
            multicastSocket.bind(new InetSocketAddress(multicastPort));
            InetAddress groupInetAddress = InetAddress.getByName(multicastAddress);
            groupSocketAddress = new InetSocketAddress(groupInetAddress, multicastPort);

            multicastSocket.joinGroup(groupSocketAddress, null);

            running = true;

        } catch (IOException e) {
            System.err.println(ANSI_RED + "Errore avvio: " + e.getMessage() + ANSI_RESET);
            throw e;
        }
    }

    public void run() {
        byte[] buffer = new byte[1024];

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.setSoTimeout(5000);
                multicastSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                processNotification(message);

            } catch (SocketTimeoutException e) {
                // non fa nulla
            } catch (IOException e) {
                if (running) {
                    System.err.println(ANSI_RED + "Errore ricezione: " + e.getMessage() + ANSI_RESET);
                }
            }
        }
    }

    private void processNotification(String jsonMessage) {
        try {
            JsonObject notification = JsonParser.parseString(jsonMessage).getAsJsonObject();

            if (!notification.has("type") || !notification.has("username")) {
                return;
            }

            String type = notification.get("type").getAsString();
            String targetUser = notification.get("username").getAsString();

            if (!this.username.equals(targetUser)) {
                return;
            }

            if ("priceThreshold".equals(type)) {
                displayPriceNotification(notification);
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore processing: " + e.getMessage() + ANSI_RESET);
        }
    }

    private void displayPriceNotification(JsonObject notification) {
        try {
            int thresholdPrice = notification.get("thresholdPrice").getAsInt();
            int currentPrice = notification.get("currentPrice").getAsInt();
            String message = notification.get("message").getAsString();

            System.out.println(ANSI_YELLOW + SEPARATOR + ANSI_RESET);
            System.out.println(ANSI_RED + "                             SOGLIA PREZZO RAGGIUNTA" + ANSI_RESET);
            System.out.println(ANSI_YELLOW +SEPARATOR + ANSI_RESET);
            System.out.println(ANSI_GREEN + "  " + message + ANSI_RESET);
            System.out.println(ANSI_GREEN + "  Soglia impostata : " + formatPrice(thresholdPrice) + " USD" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "  Prezzo attuale   : " + formatPrice(currentPrice) + " USD" + ANSI_RESET);
            System.out.println(ANSI_YELLOW + SEPARATOR + ANSI_RESET);
            System.out.print(ANSI_BOLD + ">> " + ANSI_RESET);
            System.out.flush();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore display: " + e.getMessage() + ANSI_RESET);
        }
    }

    public void stop() {
        running = false;

        try {
            if (multicastSocket != null) {
                if (groupSocketAddress != null) {
                    multicastSocket.leaveGroup(groupSocketAddress, null);
                }
                multicastSocket.close();
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore stop: " + e.getMessage() + ANSI_RESET);
        }
    }

    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }
}