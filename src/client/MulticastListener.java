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
import server.utility.Colors;

public class MulticastListener implements Runnable {

    private static final String SEPARATOR = "================================================================================";
    private MulticastSocket multicastSocket;
    private InetSocketAddress groupSocketAddress;
    private final String multicastAddress;
    private final int multicastPort;
    private final String username;
    private volatile boolean running;
    private final Object consoleLock;

    public MulticastListener(String multicastAddress, int multicastPort, String username, Object consoleLock) {
        this.multicastAddress = multicastAddress;
        this.multicastPort = multicastPort;
        this.username = username;
        this.running = false;
        this.consoleLock = consoleLock;
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
            System.err.println(Colors.RED + "Errore avvio: " + e.getMessage() + Colors.RESET);
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
            } catch (IOException e) {
                if (running) {
                    System.err.println(Colors.RED + "Errore ricezione: " + e.getMessage() + Colors.RESET);
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
            System.err.println(Colors.RED + "Errore processing: " + e.getMessage() + Colors.RESET);
        }
    }

    private void displayPriceNotification(JsonObject notification) {
        synchronized (consoleLock) {
            try {
                int thresholdPrice = notification.get("thresholdPrice").getAsInt();
                int currentPrice = notification.get("currentPrice").getAsInt();
                String message = notification.get("message").getAsString();

                System.out.println(Colors.YELLOW + SEPARATOR + Colors.RESET);
                System.out.println(Colors.RED + "                             SOGLIA PREZZO RAGGIUNTA" + Colors.RESET);
                System.out.println(Colors.YELLOW + SEPARATOR + Colors.RESET);
                System.out.println(Colors.GREEN + "  " + message + Colors.RESET);
                System.out.println(Colors.GREEN + "  Soglia impostata : " + formatPrice(thresholdPrice) + " USD" + Colors.RESET);
                System.out.println(Colors.GREEN + "  Prezzo attuale   : " + formatPrice(currentPrice) + " USD" + Colors.RESET);
                System.out.println(Colors.YELLOW + SEPARATOR + Colors.RESET);
                System.out.print(Colors.BOLD + ">> " + Colors.RESET);
                System.out.flush();

            } catch (Exception e) {
                System.err.println(Colors.RED + "Errore display: " + e.getMessage() + Colors.RESET);
            }
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
            System.err.println(Colors.RED + "Errore stop: " + e.getMessage() + Colors.RESET);
        }
    }

    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }
}