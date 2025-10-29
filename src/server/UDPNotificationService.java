package server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import server.OrderManager.Order;
import server.utility.Colors;

public class UDPNotificationService {

    private static DatagramSocket udpSocket;
    private static final ConcurrentHashMap<String, InetSocketAddress> clientAddresses = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    public static void initialize() throws IOException {
        try {
            udpSocket = new DatagramSocket();
        } catch (Exception e) {
            throw new IOException(Colors.RED + "[UDPNotification] Impossibile inizializzare servizio UDP" + Colors.RESET, e);
        }
    }

    public static void registerClient(String username, InetSocketAddress address) {
        if (username == null || address == null) {
            return;
        }

        clientAddresses.put(username, address);
        System.out.println(Colors.GREEN + "[UDPNotification] Client registrato: " + username + Colors.RESET);
    }

    public static void unregisterClient(String username) {
        if (clientAddresses.remove(username) != null) {
            System.out.println(Colors.GREEN + "[UDPNotification] Client rimosso: " + username + Colors.RESET);
        }
    }

    public static void notifyTradeExecution(Order bidOrder, Order askOrder, int tradeSize, int executionPrice) {
        try {
            JsonObject bidNotification = createTradeNotification(bidOrder, askOrder.getUsername(), tradeSize, executionPrice);

            JsonObject askNotification = createTradeNotification(askOrder, bidOrder.getUsername(), tradeSize, executionPrice);

            sendNotification(bidOrder.getUsername(), bidNotification);
            sendNotification(askOrder.getUsername(), askNotification);

        } catch (Exception e) {
            System.err.println(Colors.RED + "[UDPNotification] Errore invio notifiche: " + e.getMessage() + Colors.RESET);
        }
    }

    private static JsonObject createTradeNotification(Order userOrder, String counterparty, int tradeSize, int executionPrice) {
        JsonObject notification = new JsonObject();
        notification.addProperty("notification", "closedTrades");

        JsonArray trades = new JsonArray();
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

    private static void sendNotification(String username, JsonObject notification) {
        InetSocketAddress clientAddr = clientAddresses.get(username);

        if (clientAddr == null) {
            // se il client non è registrato non inviamo nulla
            return;
        }

        try {
            String jsonMessage = gson.toJson(notification);
            byte[] data = jsonMessage.getBytes("UTF-8");

            DatagramPacket packet = new DatagramPacket(data, data.length, clientAddr.getAddress(), clientAddr.getPort());

            udpSocket.send(packet);

        } catch (Exception e) {
            System.err.println(Colors.RED + "[UDPNotification] Errore invio a " + username + ": " + e.getMessage() + Colors.RESET);
        }
    }

    public static void shutdown() {
        try {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
                System.out.println(Colors.GREEN + "[UDPNotification] Servizio chiuso" + Colors.RESET);
            }
        } catch (Exception e) {
            System.err.println(Colors.RED + "[UDPNotification] Errore shutdown: " + e.getMessage() + Colors.RESET);
        }

        clientAddresses.clear();
    }
}