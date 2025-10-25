package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import server.utility.OrderIdGenerator;
import server.utility.TradePersistence;
import server.utility.PriceCalculator;
import server.orderbook.OrderBook;
import server.orders.StopOrderManager;
import server.orders.LimitOrderManager;
import server.orders.MarketOrderManager;

/*
 * Gestisce gli ordini del sistema di trading
 */
public class OrderManager {

    // Mappa orderId -> Order
    private static final Map<Integer, Order> allOrders = new ConcurrentHashMap<>();

    // Prezzo corrente di mercato
    private static volatile int currentMarketPrice = 58000000; // Default: 58.000 USD

    public static class Order {
        private final int orderId;        // ID univoco
        private final String username;    // Chi ha creato l'ordine
        private final String type;        // "bid" (compra) o "ask" (vende)
        private final String orderType;   // "limit", "market", "stop"
        private final int size;           // Quantità (in millesimi di BTC)
        private final int price;          // Prezzo limite
        private final int stopPrice;      // Prezzo di attivazione (per stop)
        private int remainingSize;        // Quantità ancora da eseguire

        public Order(String username, String type, String orderType, int size, int price, int stopPrice) {
            this.orderId = OrderIdGenerator.getNextOrderId();
            this.username = username;
            this.type = type;
            this.orderType = orderType;
            this.size = size;
            this.price = price;
            this.stopPrice = stopPrice;
            this.remainingSize = size;
        }

        public int getOrderId() {
            return orderId;
        }

        public String getUsername() {
            return username;
        }

        public String getType() {
            return type;
        }

        public String getOrderType() {
            return orderType;
        }

        public int getSize() {
            return size;
        }

        public int getPrice() {
            return price;
        }

        public int getStopPrice() {
            return stopPrice;
        }

        public synchronized int getRemainingSize() {
            return remainingSize;
        }

        public synchronized void setRemainingSize(int remaining) {
            this.remainingSize = remaining;
        }

        public synchronized boolean isFullyExecuted() {
            return remainingSize <= 0;
        }
    }

    public static void initialize() throws IOException {
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            throw new IOException("[OrderManager] Cartella 'data' non trovata\n");
        }

        TradePersistence.initialize();
        OrderIdGenerator.initialize();
    }

    public static int insertLimitOrder(String username, String type, int size, int price) {
        try {
            if (isInvalidOrderType(type) || size <= 0 || price <= 0) {
                return -1;
            }

            Order order = new Order(username, type, "limit", size, price, 0);
            allOrders.put(order.getOrderId(), order);

            boolean success = LimitOrderManager.insertLimitOrder(order, OrderManager::executeTrade);

            if (!success) {
                allOrders.remove(order.getOrderId());
                return -1;
            }

            System.out.println("[OrderManager] Limit order creato: " + order.getOrderId());
            return order.getOrderId();

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inserimento limit order: " + e.getMessage());
            return -1;
        }
    }

    public static int insertMarketOrder(String username, String type, int size) {
        try {
            if (isInvalidOrderType(type) || size <= 0) {
                return -1;
            }

            Order order = new Order(username, type, "market", size, 0, 0);
            allOrders.put(order.getOrderId(), order);

            boolean success = MarketOrderManager.insertMarketOrder(order, OrderManager::executeTrade);

            if (!success) {
                allOrders.remove(order.getOrderId());
                return -1;
            }

            System.out.println("[OrderManager] Market order creato: " + order.getOrderId());
            return order.getOrderId();

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inserimento market order: " + e.getMessage());
            return -1;
        }
    }

    public static int insertStopOrder(String username, String type, int size, int stopPrice) {
        try {
            if (isInvalidOrderType(type) || size <= 0 || stopPrice <= 0) {
                return -1;
            }

            // Verifica che lo stop price abbia senso
            if (!StopOrderManager.isValidStopPrice(type, stopPrice, currentMarketPrice)) {
                System.err.println("[OrderManager] Stop price non valido: " + PriceCalculator.formatPrice(stopPrice));
                return -1;
            }

            Order order = new Order(username, type, "stop", size, 0, stopPrice);
            allOrders.put(order.getOrderId(), order);
            StopOrderManager.addStopOrder(order);

            System.out.println("[OrderManager] Stop order creato: " + order.getOrderId());
            return order.getOrderId();

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inserimento stop order: " + e.getMessage());
            return -1;
        }
    }

    public static int cancelOrder(String username, int orderId) {
        try {
            Order order = allOrders.get(orderId);

            if (order == null) {
                return 101;
            }

            if (!order.getUsername().equals(username)) {
                return 101;
            }

            if (order.isFullyExecuted()) {
                return 101;
            }

            boolean removed = false;

            // Prova a rimuovere dall'OrderBook
            if ("limit".equals(order.getOrderType())) {
                removed = OrderBook.removeOrder(order);
            }
            // Prova a rimuovere dagli Stop Orders
            else if ("stop".equals(order.getOrderType())) {
                removed = StopOrderManager.removeStopOrder(orderId);
            }

            if (removed) {
                allOrders.remove(orderId);
                System.out.println("[OrderManager] Ordine cancellato: " + orderId);
                return 100;
            }

            return 101;

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore cancellazione: " + e.getMessage());
            return 101;
        }
    }

    // Genera storico per un mese
    public static JsonObject getPriceHistory(String monthYear) {
        try {
            if (monthYear == null || monthYear.length() != 6) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Formato mese non valido");
                return error;
            }

            String month = monthYear.substring(0, 2);
            String year = monthYear.substring(2, 6);

            int monthInt;
            int yearInt;

            try {
                monthInt = Integer.parseInt(month);
                yearInt = Integer.parseInt(year);

                if (monthInt < 1 || monthInt > 12) {
                    throw new NumberFormatException("Mese deve essere tra 01 e 12");
                }
            } catch (NumberFormatException e) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Formato mese non valido");
                return error;
            }

            JsonArray allRecords = TradePersistence.loadTrades();

            if (allRecords.isEmpty()) {
                JsonObject response = new JsonObject();
                response.addProperty("month", monthYear);
                response.addProperty("totalDays", 0);
                response.add("priceHistory", new JsonArray());
                return response;
            }

            // Raggruppo i trade per giorno
            Map<String, List<JsonObject>> tradesByDay = new HashMap<>();
            int filtered = 0;

            for (int i = 0; i < allRecords.size(); i++) {
                JsonObject record = allRecords.get(i).getAsJsonObject();

                if (!record.has("timestamp") || !record.has("price") || !record.has("size")) {
                    continue;
                }

                try {
                    long timestamp = record.get("timestamp").getAsLong();
                    LocalDate tradeDate = Instant.ofEpochSecond(timestamp)
                            .atZone(ZoneId.of("GMT")).toLocalDate();

                    if (tradeDate.getMonthValue() == monthInt && tradeDate.getYear() == yearInt) {
                        String dayKey = tradeDate.toString();

                        JsonObject trade = new JsonObject();
                        trade.addProperty("date", dayKey);
                        trade.addProperty("price", record.get("price").getAsInt());
                        trade.addProperty("size", record.get("size").getAsInt());
                        trade.addProperty("timestamp", timestamp);

                        if (record.has("orderId")) {
                            trade.addProperty("orderId", record.get("orderId").getAsInt());
                        }

                        tradesByDay.computeIfAbsent(dayKey, _ -> new ArrayList<>()).add(trade);
                        filtered++;
                    }

                } catch (Exception e) {
                    //non fa nulla
                }
            }

            // Calcolo storico per ogni giorno
            JsonArray historyData = new JsonArray();

            tradesByDay.keySet().stream().sorted().forEach(date -> {
                List<JsonObject> dayTrades = tradesByDay.get(date);
                JsonObject dayData = PriceCalculator.calculateDayOHLC(dayTrades, date);
                historyData.add(dayData);
            });

            JsonObject response = new JsonObject();
            response.addProperty("month", monthYear);
            response.addProperty("totalDays", historyData.size());
            response.addProperty("totalTrades", filtered);
            response.add("priceHistory", historyData);

            return response;

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore getPriceHistory: " + e.getMessage());

            JsonObject error = new JsonObject();
            error.addProperty("error", "Errore interno");
            return error;
        }
    }

    private static void executeTrade(Order bidOrder, Order askOrder, int tradeSize, int executionPrice) {
        try {
            System.out.println("[OrderManager] TRADE: " + PriceCalculator.formatSize(tradeSize) + " BTC @ " + PriceCalculator.formatPrice(executionPrice) + " USD");

            int oldPrice = currentMarketPrice;
            currentMarketPrice = executionPrice;

            if (oldPrice != currentMarketPrice) {
                PriceNotificationService.checkAndNotifyPriceThresholds(currentMarketPrice);
            }

            // Notifica gli utenti
            try {
                UDPNotificationService.notifyTradeExecution(bidOrder, askOrder, tradeSize, executionPrice);
            } catch (Exception e) {
                System.err.println("[OrderManager] Errore notifiche: " + e.getMessage());
            }

            StopOrderManager.checkAndActivateStopOrders(currentMarketPrice, OrderManager::executeTrade);

            try {
                TradePersistence.saveExecutedTrade(bidOrder, askOrder, tradeSize, executionPrice);
            } catch (IOException e) {
                System.err.println("[OrderManager] Errore salvataggio trade: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore esecuzione trade: " + e.getMessage());
        }
    }

    private static boolean isInvalidOrderType(String type) {
        return !"bid".equals(type) && !"ask".equals(type);
    }

    public static int getCurrentMarketPrice() {
        return currentMarketPrice;
    }
}