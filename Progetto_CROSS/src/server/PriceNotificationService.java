package server;

import com.google.gson.JsonObject;
import com.google.gson.Gson;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Gestisce notifiche multicast quando il prezzo supera le soglie utente
 */
public class PriceNotificationService {

    // Configurazione multicast
    private static String MULTICAST_ADDRESS;
    private static int MULTICAST_PORT;

    // Socket per invio messaggi multicast dal server
    private static DatagramSocket multicastSender;

    // Indirizzo del gruppo multicast
    private static InetAddress multicastGroup;

    // Mappa username -> soglia prezzo interessata
    private static final ConcurrentHashMap<String, Integer> userThresholds = new ConcurrentHashMap<>();

    // Gson per messaggi JSON
    private static final Gson gson = new Gson();

    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RESET = "\u001B[0m";

    public static void initialize(String multicastAddress, int multicastPort) throws IOException {
        try {
            MULTICAST_ADDRESS = multicastAddress;
            MULTICAST_PORT = multicastPort;
            multicastSender = new DatagramSocket();
            multicastGroup = InetAddress.getByName(MULTICAST_ADDRESS);

        } catch (Exception e) {
            throw new IOException(ANSI_RED + "[PriceNotification] Impossibile inizializzare servizio multicast"  + ANSI_RESET, e);
        }
    }

    public static void registerUserForPriceNotifications(String username, int thresholdPrice) {
        if (username == null || username.trim().isEmpty() || thresholdPrice <= 0) {
            return;
        }

        userThresholds.put(username, thresholdPrice);
        System.out.println(ANSI_GREEN + "[PriceNotification] " + username + " registrato per soglia: " + formatPrice(thresholdPrice) + ANSI_RESET);
    }

    public static void unregisterUser(String username) {
        if (userThresholds.remove(username) != null) {
            System.out.println(ANSI_GREEN + "[PriceNotification] Utente rimosso: " + username + ANSI_RESET);
        }
    }

    public static void checkAndNotifyPriceThresholds(int newPrice) {
        if (userThresholds.isEmpty()) {
            return;
        }

        try {
            for (ConcurrentHashMap.Entry<String, Integer> entry : userThresholds.entrySet()) {
                String username = entry.getKey();
                int threshold = entry.getValue();

                if (newPrice >= threshold) {
                    sendNotification(username, threshold, newPrice);
                    userThresholds.remove(username);
                }
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "[PriceNotification] Errore controllo soglie: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void sendNotification(String username, int threshold, int currentPrice) {
        try {
            JsonObject notification = new JsonObject();
            notification.addProperty("type", "priceThreshold");
            notification.addProperty("username", username);
            notification.addProperty("thresholdPrice", threshold);
            notification.addProperty("currentPrice", currentPrice);
            notification.addProperty("message", "Soglia prezzo superata!");
            notification.addProperty("timestamp", System.currentTimeMillis());

            String jsonMessage = gson.toJson(notification);
            byte[] data = jsonMessage.getBytes("UTF-8");

            DatagramPacket packet = new DatagramPacket(data, data.length, multicastGroup, MULTICAST_PORT);

            multicastSender.send(packet);

            System.out.println(ANSI_GREEN + "[PriceNotification] Notifica inviata a " + username + ANSI_RESET);

        } catch (Exception e) {
            System.err.println(ANSI_RED + "[PriceNotification] Errore invio notifica: " + e.getMessage() + ANSI_RESET);
        }
    }

    public static JsonObject getMulticastInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("multicastAddress", MULTICAST_ADDRESS);
        info.addProperty("multicastPort", MULTICAST_PORT);
        info.addProperty("activeUsers", userThresholds.size());
        return info;
    }

    public static void shutdown() {
        try {
            if (multicastSender != null && !multicastSender.isClosed()) {
                multicastSender.close();
                System.out.println(ANSI_GREEN + "[PriceNotification] Servizio chiuso" + ANSI_RESET);
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "[PriceNotification] Errore shutdown: " + e.getMessage() + ANSI_RESET);
        }

        userThresholds.clear();
    }

    private static String formatPrice(int priceInMillis) {

        return String.format("%,.0f", priceInMillis / 1000.0);
    }
}