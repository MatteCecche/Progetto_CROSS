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

    private static final String SEPARATOR = "============================================================";

    // Socket multicast per ricezione messaggi
    private MulticastSocket multicastSocket;

    // Indirizzo del gruppo come SocketAddress (non deprecato)
    private InetSocketAddress groupSocketAddress;

    // Indirizzo e porta del gruppo multicast
    private final String multicastAddress;
    private final int multicastPort;

    // Username dell'utente per logging
    private final String username;

    // Flag per controllo esecuzione thread
    private volatile boolean running;

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
            System.err.println("[Multicast] Errore avvio: " + e.getMessage());
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
                    System.err.println("[Multicast] Errore ricezione: " + e.getMessage());
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
            System.err.println("[Multicast] Errore processing: " + e.getMessage());
        }
    }

    private void displayPriceNotification(JsonObject notification) {
        try {
            int thresholdPrice = notification.get("thresholdPrice").getAsInt();
            int currentPrice = notification.get("currentPrice").getAsInt();
            String message = notification.get("message").getAsString();

            System.out.println("\n" + SEPARATOR);
            System.out.println("           SOGLIA PREZZO RAGGIUNTA");
            System.out.println(SEPARATOR);
            System.out.println("  " + message);
            System.out.println("  Soglia impostata : " + formatPrice(thresholdPrice) + " USD");
            System.out.println("  Prezzo attuale   : " + formatPrice(currentPrice) + " USD");
            System.out.println(SEPARATOR);
            System.out.print(">> ");
            System.out.flush();

        } catch (Exception e) {
            System.err.println("[Multicast] Errore display: " + e.getMessage());
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
            System.err.println("[Multicast] Errore stop: " + e.getMessage());
        }
    }

    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }
}