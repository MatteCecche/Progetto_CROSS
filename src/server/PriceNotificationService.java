package server;

import com.google.gson.JsonObject;
import com.google.gson.Gson;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import server.utility.Colors;

public class PriceNotificationService {

    private static String MULTICAST_ADDRESS;
    private static int MULTICAST_PORT;
    private static DatagramSocket multicastSender;
    private static InetAddress multicastGroup;
    private static final ConcurrentHashMap<String, Integer> userThresholds = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    public static void initialize(String multicastAddress, int multicastPort) throws IOException {
        try {
            MULTICAST_ADDRESS = multicastAddress;
            MULTICAST_PORT = multicastPort;
            multicastSender = new DatagramSocket();
            multicastGroup = InetAddress.getByName(MULTICAST_ADDRESS);

        } catch (Exception e) {
            throw new IOException(Colors.RED + "[PriceNotification] Impossibile inizializzare servizio multicast"  + Colors.RESET, e);
        }
    }

    public static void registerUserForPriceNotifications(String username, int thresholdPrice) {
        if (username == null || username.trim().isEmpty() || thresholdPrice <= 0) {
            return;
        }

        userThresholds.put(username, thresholdPrice);
        System.out.println(Colors.GREEN + "[PriceNotification] " + username + " registrato per soglia: " + formatPrice(thresholdPrice) + Colors.RESET);
    }

    public static void unregisterUser(String username) {
        if (userThresholds.remove(username) != null) {
            System.out.println(Colors.GREEN + "[PriceNotification] Utente rimosso: " + username + Colors.RESET);
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
            System.err.println(Colors.RED + "[PriceNotification] Errore controllo soglie: " + e.getMessage() + Colors.RESET);
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

            System.out.println(Colors.GREEN + "[PriceNotification] Notifica inviata a " + username + Colors.RESET);

        } catch (Exception e) {
            System.err.println(Colors.RED + "[PriceNotification] Errore invio notifica: " + e.getMessage() + Colors.RESET);
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
                System.out.println(Colors.GREEN + "[PriceNotification] Servizio chiuso" + Colors.RESET);
            }
        } catch (Exception e) {
            System.err.println(Colors.RED + "[PriceNotification] Errore shutdown: " + e.getMessage() + Colors.RESET);
        }

        userThresholds.clear();
    }

    private static String formatPrice(int priceInMillis) {

        return String.format("%,.0f", priceInMillis / 1000.0);
    }
}