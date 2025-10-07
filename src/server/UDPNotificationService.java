package server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.OrderManager.Order;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servizio UDP per notifiche di trade eseguiti
 * Implementa la comunicazione best-effort con i client quando ordini vengono finalizzati
 */
public class UDPNotificationService {

    // Socket UDP per invio notifiche
    private static DatagramSocket udpSocket;

    // Mappa thread-safe: username -> indirizzo UDP client
    private static final ConcurrentHashMap<String, InetSocketAddress> clientAddresses =
            new ConcurrentHashMap<>();

    // Gson per serializzazione JSON
    private static final Gson gson = new Gson();

    //Inizializza il servizio di notifiche UDP
    public static void initialize() throws IOException {
        try {
            udpSocket = new DatagramSocket();
        } catch (Exception e) {
            System.err.println("[UDPNotificationService] Errore inizializzazione: " + e.getMessage());
            throw new IOException("Impossibile inizializzare servizio notifiche UDP", e);
        }
    }

    //Registra l'indirizzo UDP di un client per ricevere notifiche
    public static void registerClient(String username, InetSocketAddress address) {
        if (username == null || address == null) {
            System.err.println("[UDPNotificationService] Parametri non validi per registrazione client");
            return;
        }

        clientAddresses.put(username, address);
        System.out.println("[UDPNotificationService] Client registrato: " + username +
                " @ " + address.getAddress().getHostAddress() + ":" + address.getPort());
    }

    //Rimuove la registrazione di un client
    public static void unregisterClient(String username) {
        if (clientAddresses.remove(username) != null) {
            System.out.println("[UDPNotificationService] Client rimosso: " + username);
        }
    }

    //Invia notifica UDP quando un trade viene eseguito
    public static void notifyTradeExecution(Order bidOrder, Order askOrder, int tradeSize, int executionPrice) {
        try {
            // Crea notifica per l'acquirente (bid)
            JsonObject bidNotification = createSingleTradeNotification(
                    bidOrder,
                    askOrder.getUsername(),
                    tradeSize,
                    executionPrice
            );

            // Crea notifica per il venditore (ask)
            JsonObject askNotification = createSingleTradeNotification(
                    askOrder,
                    bidOrder.getUsername(),
                    tradeSize,
                    executionPrice
            );

            // Invia notifiche separate
            sendUDPNotification(bidOrder.getUsername(), bidNotification);
            sendUDPNotification(askOrder.getUsername(), askNotification);

            System.out.println("[UDPNotificationService] Notifiche trade inviate a " +
                    bidOrder.getUsername() + " e " + askOrder.getUsername());

        } catch (Exception e) {
            System.err.println("[UDPNotificationService] Errore invio notifiche: " + e.getMessage());
        }
    }

    //Crea il messaggio JSON di notifica per un singolo utente
    private static JsonObject createSingleTradeNotification(Order userOrder, String counterparty, int tradeSize, int executionPrice) {
        JsonObject notification = new JsonObject();
        notification.addProperty("notification", "closedTrades");

        JsonArray trades = new JsonArray();

        // Informazioni del trade per questo specifico utente
        JsonObject trade = new JsonObject();
        trade.addProperty("orderId", userOrder.getOrderId());
        trade.addProperty("type", userOrder.getType());
        trade.addProperty("orderType", userOrder.getOrderType());
        trade.addProperty("size", tradeSize);
        trade.addProperty("price", executionPrice);
        trade.addProperty("counterparty", counterparty);
        trade.addProperty("timestamp", System.currentTimeMillis() / 1000);

        trades.add(trade);
        notification.add("trades", trades);

        return notification;
    }

    //Invia notifica UDP a un singolo client
    private static void sendUDPNotification(String username, JsonObject notification) {
        InetSocketAddress clientAddr = clientAddresses.get(username);

        if (clientAddr == null) {
            // Client non registrato o offline
            System.out.println("[UDPNotificationService] Client " + username + " non registrato, notifica non inviata (best effort)");
            return;
        }

        try {
            String jsonMessage = gson.toJson(notification);
            byte[] data = jsonMessage.getBytes("UTF-8");

            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    clientAddr.getAddress(),
                    clientAddr.getPort()
            );

            udpSocket.send(packet);

            System.out.println("[UDPNotificationService] Notifica UDP inviata a " + username);

        } catch (Exception e) {
            System.err.println("[UDPNotificationService] Impossibile inviare notifica a " + username + ": " + e.getMessage());
        }
    }

    //Verifica se un client è registrato per ricevere notifiche
    public static boolean isClientRegistered(String username) {
        return clientAddresses.containsKey(username);
    }

    //Ottiene il numero di client attualmente registrati
    public static int getRegisteredClientsCount() {
        return clientAddresses.size();
    }

    //Ottiene statistiche del servizio
    public static JsonObject getStats() {
        JsonObject stats = new JsonObject();
        stats.addProperty("registeredClients", clientAddresses.size());
        stats.addProperty("socketOpen", udpSocket != null && !udpSocket.isClosed());
        return stats;
    }

    //Chiude il servizio e rilascia le risorse
    public static void shutdown() {
        try {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
                System.out.println("[UDPNotificationService] Servizio chiuso");
            }
        } catch (Exception e) {
            System.err.println("[UDPNotificationService] Errore durante shutdown: " + e.getMessage());
        }

        clientAddresses.clear();
    }
}