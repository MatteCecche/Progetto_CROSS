package server;

import com.google.gson.JsonObject;
import com.google.gson.Gson;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servizio multicast UDP per notifiche di soglie di prezzo BTC
 * - Gestisce registrazioni utenti per notifiche di soglia prezzo
 * - Monitora variazioni del prezzo di mercato BTC
 * - Invia notifiche multicast UDP quando soglie vengono superate
 */
public class PriceNotificationService {

    // Configurazione multicast
    private static String MULTICAST_ADDRESS;
    private static int MULTICAST_PORT;

    // Socket per invio messaggi multicast dal server
    private static DatagramSocket multicastSender;

    // Indirizzo del gruppo multicast
    private static InetAddress multicastGroup;

    // Mappa thread-safe: username -> soglia prezzo interessata
    private static final ConcurrentHashMap<String, Integer> userThresholds = new ConcurrentHashMap<>();

    // Gson per serializzazione messaggi JSON
    private static final Gson gson = new Gson();

    //Inizializza il servizio di notifiche multicast
    public static void initialize(String multicastAddress, int multicastPort) throws IOException {
        try {
            // Imposta parametri da configurazione
            MULTICAST_ADDRESS = multicastAddress;
            MULTICAST_PORT = multicastPort;

            // Crea socket per invio messaggi multicast
            multicastSender = new DatagramSocket();

            // Imposta indirizzo del gruppo multicast
            multicastGroup = InetAddress.getByName(MULTICAST_ADDRESS);


        } catch (Exception e) {
            System.err.println("[PriceNotificationService] Errore inizializzazione multicast: " + e.getMessage());
            throw new IOException("Impossibile inizializzare servizio multicast", e);
        }
    }

    //Registra un utente per ricevere notifiche quando il prezzo BTC supera una soglia
    public static void registerUserForPriceNotifications(String username, int thresholdPrice) {
        if (username == null || username.trim().isEmpty()) {
            System.err.println("[PriceNotificationService] Username non valido per registrazione notifiche");
            return;
        }

        if (thresholdPrice <= 0) {
            System.err.println("[PriceNotificationService] Soglia prezzo non valida: " + thresholdPrice);
            return;
        }

        userThresholds.put(username, thresholdPrice);
        System.out.println("[PriceNotificationService] Utente " + username +
                " registrato per notifiche prezzo > " + formatPrice(thresholdPrice) + " USD");
    }

    //Rimuove la registrazione di un utente dalle notifiche
    public static void unregisterUser(String username) {
        if (userThresholds.remove(username) != null) {
            System.out.println("[PriceNotificationService] Utente " + username + " rimosso dalle notifiche prezzo");
        }
    }

    //Controlla se il nuovo prezzo supera soglie di utenti registrati e invia notifiche
    public static void checkAndNotifyPriceThresholds(int newPrice) {
        if (userThresholds.isEmpty()) {
            return; // Nessun utente interessato alle notifiche
        }

        try {
            // Controlla per ogni utente registrato se la soglia è stata superata
            for (ConcurrentHashMap.Entry<String, Integer> entry : userThresholds.entrySet()) {
                String username = entry.getKey();
                int threshold = entry.getValue();

                // Soglia superata: prezzo corrente >= soglia utente
                if (newPrice >= threshold) {
                    sendPriceThresholdNotification(username, threshold, newPrice);

                    // Rimuovi utente dopo notifica
                    userThresholds.remove(username);
                }
            }

        } catch (Exception e) {
            System.err.println("[PriceNotificationService] Errore controllo soglie prezzo: " + e.getMessage());
        }
    }

    //Invia notifica multicast UDP per soglia prezzo superata
    private static void sendPriceThresholdNotification(String username, int threshold, int currentPrice) {
        try {
            // Crea messaggio JSON per notifica multicast
            JsonObject notification = new JsonObject();
            notification.addProperty("type", "priceThreshold");
            notification.addProperty("username", username);
            notification.addProperty("thresholdPrice", threshold);
            notification.addProperty("currentPrice", currentPrice);
            notification.addProperty("message", "Soglia prezzo BTC superata!");
            notification.addProperty("timestamp", System.currentTimeMillis());

            // Serializza messaggio in JSON
            String jsonMessage = gson.toJson(notification);
            byte[] messageBytes = jsonMessage.getBytes("UTF-8");

            // Crea DatagramPacket per multicast seguendo le slide
            DatagramPacket packet = new DatagramPacket(
                    messageBytes,
                    messageBytes.length,
                    multicastGroup,
                    MULTICAST_PORT
            );

            // Invia messaggio al gruppo multicast
            multicastSender.send(packet);

            System.out.println("[PriceNotificationService] Notifica multicast inviata per " + username +
                    " - Soglia " + formatPrice(threshold) + " superata (prezzo: " + formatPrice(currentPrice) + ")");

        } catch (Exception e) {
            System.err.println("[PriceNotificationService] Errore invio notifica multicast: " + e.getMessage());
        }
    }

    //Ottieni informazioni sul servizio multicast per i client
    public static JsonObject getMulticastInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("multicastAddress", MULTICAST_ADDRESS);
        info.addProperty("multicastPort", MULTICAST_PORT);
        info.addProperty("activeUsers", userThresholds.size());
        return info;
    }

    //Chiude il servizio multicast e rilascia le risorse
    public static void shutdown() {
        try {
            if (multicastSender != null && !multicastSender.isClosed()) {
                multicastSender.close();
                System.out.println("[PriceNotificationService] Servizio multicast chiuso");
            }
        } catch (Exception e) {
            System.err.println("[PriceNotificationService] Errore durante shutdown: " + e.getMessage());
        }

        userThresholds.clear();
    }

    //Formatta prezzo in millesimi
    private static String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    //Ottieni statistiche utenti registrati per debug
    public static int getRegisteredUsersCount() {
        return userThresholds.size();
    }
}