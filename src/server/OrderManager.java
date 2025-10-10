package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import server.utility.OrderIdGenerator;
import server.utility.TradePersistence;
import server.utility.PriceCalculator;
import server.orderbook.OrderBook;
import server.orders.StopOrderManager;
import server.orders.LimitOrderManager;
import server.orders.MarketOrderManager;

/**
 * Coordinatore centrale del sistema di trading CROSS.
 * - Interfacce pubbliche per inserimento ordini (Limit, Market, Stop)
 * - Coordinamento tra componenti specializzati
 * - Gestione stato generale del sistema (prezzo corrente, allOrders)
 * - Storico prezzi OHLC per analisi mensili
 * - Esecuzione trade con persistenza e notifiche
 * - Inizializzazione sistema completo
 * - Notifiche multicast UDP per soglie prezzo
 * - Notifiche UDP best-effort per trade
 * - Storico OHLC mensile
 */
public class OrderManager {

    // Mappa orderId -> Order per lookup veloce e cancellazioni
    private static final Map<Integer, Order> allOrders = new ConcurrentHashMap<>();

    // Prezzo corrente di mercato (ultimo trade eseguito)
    private static volatile int currentMarketPrice = 58000000; // Default: 58.000 USD

    //Classe interna che rappresenta un ordine nel sistema CROSS.
    public static class Order {
        private final int orderId;
        private final String username;
        private final String type;        // "bid" (buy) o "ask" (sell)
        private final String orderType;   // "limit", "market", "stop"
        private final int size;           // Quantità in millesimi di BTC
        private final int price;          // Prezzo in millesimi di USD (0 per market orders)
        private final int stopPrice;      // Stop price per stop orders (0 per altri)
        private final long timestamp;     // Timestamp per time priority
        private int remainingSize;        // Size rimanente (per matching parziali)

        //Costruisce un nuovo ordine con ID auto-generato.
        public Order(String username, String type, String orderType, int size, int price, int stopPrice) {
            this.orderId = OrderIdGenerator.getNextOrderId();
            this.username = username;
            this.type = type;
            this.orderType = orderType;
            this.size = size;
            this.price = price;
            this.stopPrice = stopPrice;
            this.timestamp = System.currentTimeMillis();
            this.remainingSize = size;
        }

        // Getters per accesso thread-safe ai dati dell'ordine
        public int getOrderId() { return orderId; }
        public String getUsername() { return username; }
        public String getType() { return type; }
        public String getOrderType() { return orderType; }
        public int getSize() { return size; }
        public int getPrice() { return price; }
        public int getStopPrice() { return stopPrice; }
        public long getTimestamp() { return timestamp; }

        //Ottiene la quantità rimanente dell'ordine
        public synchronized int getRemainingSize() { return remainingSize; }

        //Imposta la quantità rimanente dell'ordine
        public synchronized void setRemainingSize(int remaining) {
            this.remainingSize = remaining;
        }

        //Verifica se l'ordine è stato completamente eseguito
        public synchronized boolean isFullyExecuted() {
            return remainingSize <= 0;
        }
    }

    //Inizializza il sistema di gestione ordini
    public static void initialize() throws IOException {
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            throw new IOException("[OrderManager] Errore: Directory 'data/' non trovata!\n");
        }
        // Inizializza persistenza trade
        TradePersistence.initialize();

        // Inizializza il generatore di orderID
        OrderIdGenerator.initialize();
    }

    //Inserisce un Limit Order nel sistema
    public static int insertLimitOrder(String username, String type, int size, int price) {
        try {
            // Validazione parametri
            if (!isValidOrderType(type) || size <= 0 || price <= 0) {
                System.err.println("[OrderManager] Parametri Limit Order non validi");
                return -1;
            }

            // Creazione ordine Limit
            Order order = new Order(username, type, "limit", size, price, 0);
            allOrders.put(order.getOrderId(), order);

            System.out.println("[OrderManager] Creato Limit Order " + order.getOrderId());

            // Delega a LimitOrderManager con callback per esecuzione trade
            boolean success = LimitOrderManager.insertLimitOrder(order, OrderManager::executeTrade);

            if (!success) {
                allOrders.remove(order.getOrderId());
                return -1;
            }

            return order.getOrderId();

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inserimento Limit Order: " + e.getMessage());
            return -1;
        }
    }

    //Inserisce un Market Order nel sistema con esecuzione immediata
    public static int insertMarketOrder(String username, String type, int size) {
        try {
            // Validazione parametri
            if (!isValidOrderType(type) || size <= 0) {
                System.err.println("[OrderManager] Parametri Market Order non validi");
                return -1;
            }

            // Creazione ed esecuzione immediata Market Order
            Order marketOrder = new Order(username, type, "market", size, 0, 0);
            allOrders.put(marketOrder.getOrderId(), marketOrder);

            System.out.println("[OrderManager] Creato Market Order " + marketOrder.getOrderId());

            // Delega a MarketOrderManager con callback per esecuzione trade
            boolean success = MarketOrderManager.insertMarketOrder(marketOrder, OrderManager::executeTrade);

            if (!success) {
                allOrders.remove(marketOrder.getOrderId());
                return -1;
            }

            return marketOrder.getOrderId();

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inserimento Market Order: " + e.getMessage());
            return -1;
        }
    }

    //Inserisce un Stop Order nel sistema
    public static int insertStopOrder(String username, String type, int size, int stopPrice) {
        try {
            // Validazione parametri
            if (!isValidOrderType(type) || size <= 0 || stopPrice <= 0) {
                System.err.println("[OrderManager] Parametri Stop Order non validi: type=" + type +
                        ", size=" + size + ", stopPrice=" + stopPrice);
                return -1;
            }

            // Validazione logica stop price vs prezzo corrente
            if (!StopOrderManager.isValidStopPrice(type, stopPrice, currentMarketPrice)) {
                System.err.println("[OrderManager] Stop Price non valido per " + type +
                        ": " + PriceCalculator.formatPrice(stopPrice) + " (prezzo corrente: " +
                        PriceCalculator.formatPrice(currentMarketPrice) + ")");
                return -1;
            }

            // Creazione Stop Order
            Order stopOrder = new Order(username, type, "stop", size, 0, stopPrice);
            allOrders.put(stopOrder.getOrderId(), stopOrder);
            StopOrderManager.addStopOrder(stopOrder);

            System.out.println("[OrderManager] Creato Stop Order " + stopOrder.getOrderId() +
                    " - " + username + " " + type + " " + PriceCalculator.formatSize(size) +
                    " BTC @ stop " + PriceCalculator.formatPrice(stopPrice) + " USD");

            return stopOrder.getOrderId();

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inserimento Stop Order: " + e.getMessage());
            return -1;
        }
    }

    //Cancella un ordine dal sistema
    public static int cancelOrder(String username, int orderId) {
        try {
            Order order = allOrders.get(orderId);

            // Controllo esistenza ordine
            if (order == null) {
                System.err.println("[OrderManager] Cancel fallito: ordine " + orderId + " non esistente");
                return 101;
            }

            // Controllo ownership
            if (!order.getUsername().equals(username)) {
                System.err.println("[OrderManager] Cancel fallito: ordine " + orderId +
                        " appartiene a " + order.getUsername());
                return 101;
            }

            // Controllo se ordine già eseguito completamente
            if (order.isFullyExecuted()) {
                System.err.println("[OrderManager] Cancel fallito: ordine " + orderId +
                        " già completamente eseguito");
                return 101;
            }

            // Rimozione dall'order book appropriato
            boolean removed = OrderBook.removeOrder(order);
            StopOrderManager.removeStopOrder(orderId);  // Rimuovi anche da stop orders se presente

            if (removed) {
                allOrders.remove(orderId);
                System.out.println("[OrderManager] Ordine " + orderId + " cancellato con successo");
                return 100;
            } else {
                System.err.println("[OrderManager] Errore rimozione ordine " + orderId + " dall'order book");
                return 101;
            }

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore cancellazione ordine: " + e.getMessage());
            return 101;
        }
    }

    //Genera storico prezzi OHLC (Open/High/Low/Close) per un mese specifico
    public static JsonObject getPriceHistory(String monthYear) {
        try {
            // Validazione formato input
            if (monthYear == null || monthYear.length() != 6) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Formato mese non valido. Usare MMYYYY (es: 012025)");
                return error;
            }

            String month = monthYear.substring(0, 2);
            String year = monthYear.substring(2, 6);

            int monthInt;
            int yearInt;
            try {
                monthInt = Integer.parseInt(month);
                yearInt = Integer.parseInt(year);

                // Validazione mese (1-12)
                if (monthInt < 1 || monthInt > 12) {
                    throw new NumberFormatException("Mese deve essere tra 01 e 12");
                }
            } catch (NumberFormatException e) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Formato mese non valido: " + e.getMessage());
                return error;
            }

            // Carica tutti i record dal file
            JsonArray allRecords = TradePersistence.loadTrades();

            if (allRecords.size() == 0) {
                JsonObject response = new JsonObject();
                response.addProperty("month", monthYear);
                response.addProperty("totalDays", 0);
                response.add("priceHistory", new JsonArray());
                response.addProperty("message", "Nessun record trovato nel file");
                return response;
            }

            // Filtra solo i trade eseguiti del mese richiesto
            Map<String, List<JsonObject>> tradesByDay = new HashMap<>();
            int recordsProcessed = 0;
            int tradesFiltered = 0;

            for (int i = 0; i < allRecords.size(); i++) {
                JsonObject record = allRecords.get(i).getAsJsonObject();
                recordsProcessed++;

                // Verifica che il record sia un trade con tutti i campi necessari
                if (!record.has("timestamp") || !record.has("price") || !record.has("size")) {
                    continue;
                }

                try {
                    // Converti timestamp UNIX a LocalDate
                    long timestamp = record.get("timestamp").getAsLong();
                    LocalDate tradeDate = Instant.ofEpochSecond(timestamp)
                            .atZone(ZoneId.of("GMT"))
                            .toLocalDate();

                    // Filtra per mese/anno richiesto
                    if (tradeDate.getMonthValue() == monthInt && tradeDate.getYear() == yearInt) {
                        String dayKey = tradeDate.toString(); // Formato YYYY-MM-DD

                        // Crea trade normalizzato per calcolo OHLC
                        JsonObject tradeForOHLC = new JsonObject();
                        tradeForOHLC.addProperty("date", dayKey);
                        tradeForOHLC.addProperty("price", record.get("price").getAsInt());
                        tradeForOHLC.addProperty("size", record.get("size").getAsInt());
                        tradeForOHLC.addProperty("timestamp", timestamp);

                        // Mantieni metadati originali se presenti
                        if (record.has("orderId")) {
                            tradeForOHLC.addProperty("orderId", record.get("orderId").getAsInt());
                        }
                        if (record.has("type")) {
                            tradeForOHLC.addProperty("type", record.get("type").getAsString());
                        }
                        if (record.has("orderType")) {
                            tradeForOHLC.addProperty("orderType", record.get("orderType").getAsString());
                        }

                        tradesByDay.computeIfAbsent(dayKey, k -> new ArrayList<>()).add(tradeForOHLC);
                        tradesFiltered++;
                    }

                } catch (Exception e) {
                    System.err.println("[OrderManager] Errore processing record " + i + ": " + e.getMessage());
                    continue;
                }
            }

            System.out.println("[OrderManager] Record processati: " + recordsProcessed +
                    ", trade filtrati per " + monthYear + ": " + tradesFiltered);

            // Calcola OHLC per ogni giorno
            JsonArray historyData = new JsonArray();

            // Ordina date e calcola OHLC per ciascuna
            tradesByDay.keySet().stream()
                    .sorted()
                    .forEach(date -> {
                        List<JsonObject> dayTrades = tradesByDay.get(date);
                        // Usa PriceCalculator per calcolare OHLC del giorno
                        JsonObject dayData = PriceCalculator.calculateDayOHLC(dayTrades, date);
                        historyData.add(dayData);
                    });

            // Crea risposta finale conforme alle specifiche
            JsonObject response = new JsonObject();
            response.addProperty("month", monthYear);
            response.addProperty("totalDays", historyData.size());
            response.addProperty("totalTrades", tradesFiltered);
            response.add("priceHistory", historyData);

            System.out.println("[OrderManager] Generato storico prezzi per " + monthYear +
                    ": " + historyData.size() + " giorni con dati (" + tradesFiltered + " trade)");

            return response;

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore getPriceHistory: " + e.getMessage());
            e.printStackTrace();

            JsonObject error = new JsonObject();
            error.addProperty("error", "Errore interno durante generazione storico prezzi");
            error.addProperty("details", e.getMessage());
            return error;
        }
    }

    //Esegue un singolo trade tra due ordini
    private static void executeTrade(Order bidOrder, Order askOrder, int tradeSize, int executionPrice) {
        try {
            System.out.println("[OrderManager] TRADE ESEGUITO: " +
                    PriceCalculator.formatSize(tradeSize) + " BTC @ " +
                    PriceCalculator.formatPrice(executionPrice) + " USD " +
                    "(Bid: " + bidOrder.getOrderId() + ", Ask: " + askOrder.getOrderId() + ")");

            // Aggiorna prezzo di mercato con il prezzo dell'ultimo trade
            int oldPrice = currentMarketPrice;
            currentMarketPrice = executionPrice;

            // Log cambio prezzo se significativo
            if (oldPrice != currentMarketPrice) {
                System.out.println("[OrderManager] Prezzo aggiornato: " +
                        PriceCalculator.formatPrice(oldPrice) + " → " +
                        PriceCalculator.formatPrice(currentMarketPrice) + " USD");

                // Chiama servizio multicast per controllo soglie utenti (vecchio ordinamento)
                PriceNotificationService.checkAndNotifyPriceThresholds(currentMarketPrice);
            }

            // Invia notifiche UDP agli utenti coinvolti (best effort)
            try {
                UDPNotificationService.notifyTradeExecution(
                        bidOrder, askOrder, tradeSize, executionPrice
                );
            } catch (Exception e) {
                // Best effort: errore nelle notifiche non blocca il trade
                System.err.println("[OrderManager] Errore invio notifiche UDP: " + e.getMessage());
            }

            // Controlla e attiva eventuali Stop Orders
            StopOrderManager.checkAndActivateStopOrders(currentMarketPrice, OrderManager::executeTrade);

            // Salva trade nel file storico (genera 2 record: uno per bid, uno per ask)
            try {
                TradePersistence.saveExecutedTrade(bidOrder, askOrder, tradeSize, executionPrice);
            } catch (IOException e) {
                System.err.println("[OrderManager] Errore salvataggio trade: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore esecuzione trade: " + e.getMessage());
        }
    }

    //Valida che il tipo di ordine sia bid o ask
    private static boolean isValidOrderType(String type) {
        return "bid".equals(type) || "ask".equals(type);
    }

    //Ottiene il prezzo corrente di mercato BTC
    public static int getCurrentMarketPrice() {
        return currentMarketPrice;
    }
}