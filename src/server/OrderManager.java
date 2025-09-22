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
import server.orderbook.MatchingEngine;
import java.util.stream.Collectors;

/**
 * Coordinatore principale del sistema di trading CROSS
 *
 * Responsabilità dopo refactoring:
 * - Interfacce pubbliche per inserimento ordini (Limit, Market, Stop)
 * - Coordinamento tra componenti specializzati
 * - Gestione stato generale del sistema (prezzo corrente, allOrders)
 * - Storico prezzi OHLC per analisi mensili
 * - Esecuzione trade con persistenza e notifiche
 * - Inizializzazione sistema completo
 *
 * Componenti delegati:
 * - OrderBook: strutture dati bid/ask
 * - MatchingEngine: algoritmi price/time priority
 * - StopOrderManager: gestione Stop Orders
 * - PriceCalculator: formattazione e calcoli OHLC
 * - TradePersistence: salvataggio trade in JSON
 * - OrderIdGenerator: generazione ID univoci
 *
 * Thread-safe per uso in ambiente multithreaded del server
 * Size e Price sono espressi in millesimi secondo specifiche progetto
 */
public class OrderManager {

    // === ORDER BOOK STRUCTURES (Thread-Safe) ===

    // Mappa orderId -> Order per lookup veloce e cancellazioni
    private static final Map<Integer, Order> allOrders = new ConcurrentHashMap<>();

    // Prezzo corrente di mercato (ultimo trade eseguito)
    private static volatile int currentMarketPrice = 58000000; // Default: 58.000 USD

    /**
     * Classe interna che rappresenta un ordine nel sistema CROSS
     * Contiene tutti i dati necessari per gestione order book e matching
     */
    public static class Order {
        private final int orderId;
        private final String username;
        private final String type;        // "bid" (buy) o "ask" (sell)
        private final String orderType;   // "limit", "market", "stop"
        private final int size;           // Quantità in millesimi di BTC
        private final int price;          // Prezzo in millesimi di USD (0 per market orders)
        private final int stopPrice;     // Stop price per stop orders (0 per altri)
        private final long timestamp;    // Timestamp per time priority
        private int remainingSize;       // Size rimanente (per matching parziali)

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
        public synchronized int getRemainingSize() { return remainingSize; }

        public synchronized void setRemainingSize(int remaining) {
            this.remainingSize = remaining;
        }

        public synchronized boolean isFullyExecuted() {
            return remainingSize <= 0;
        }
    }


    // === INIZIALIZZAZIONE SISTEMA ===

    /**
     * Initialize semplificato - usa solo StoricoOrdini.json
     * Verifica/crea il file unificato e inizializza il generatore orderID
     */
    public static void initialize() throws IOException {
        // Crea directory data se non esiste
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            System.out.println("[OrderManager] Creata directory data/");
        }

        // Inizializza persistenza trade
        TradePersistence.initialize();

        // Inizializza il generatore di orderID
        OrderIdGenerator.initialize();

        System.out.println("[OrderManager] Sistema gestione ordini inizializzato");
        System.out.println("[OrderManager] Prezzo corrente BTC: " + PriceCalculator.formatPrice(currentMarketPrice) + " USD");
    }

    // === OPERAZIONI ORDINI ===

    /**
     * Inserisce un Limit Order nel sistema
     * Formato JSON secondo ALLEGATO 1: type=bid/ask, size=NUMBER, price=NUMBER
     *
     * @param username utente proprietario dell'ordine
     * @param type "bid" per acquisto, "ask" per vendita
     * @param size quantità in millesimi di BTC
     * @param price prezzo limite in millesimi di USD
     * @return orderId se successo, -1 se errore
     */
    public static int insertLimitOrder(String username, String type, int size, int price) {
        try {
            // Validazione parametri di input
            if (!isValidOrderType(type) || size <= 0 || price <= 0) {
                System.err.println("[OrderManager] Parametri Limit Order non validi: type=" + type +
                        ", size=" + size + ", price=" + price);
                return -1;
            }

            // Creazione ordine Limit
            Order order = new Order(username, type, "limit", size, price, 0);
            allOrders.put(order.getOrderId(), order);

            System.out.println("[OrderManager] Creato Limit Order " + order.getOrderId() +
                    " - " + username + " " + type + " " + PriceCalculator.formatSize(size) + " BTC @ " + PriceCalculator.formatPrice(price) + " USD");

            // Inserimento nell'order book appropriato
            if ("bid".equals(type)) {
                OrderBook.addToBidBook(order);
            } else {
                OrderBook.addToAskBook(order);
            }

            // Tentativo di matching immediato
            MatchingEngine.performLimitOrderMatching(OrderManager::executeTrade);

            return order.getOrderId();

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inserimento Limit Order: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Inserisce un Market Order nel sistema (esecuzione immediata)
     * Formato JSON secondo ALLEGATO 1: type=bid/ask, size=NUMBER
     *
     * @param username utente proprietario dell'ordine
     * @param type "bid" per acquisto, "ask" per vendita
     * @param size quantità in millesimi di BTC
     * @return orderId se successo, -1 se errore (incluso order book vuoto)
     */
    public static int insertMarketOrder(String username, String type, int size) {
        try {
            // Validazione parametri
            if (!isValidOrderType(type) || size <= 0) {
                System.err.println("[OrderManager] Parametri Market Order non validi: type=" + type + ", size=" + size);
                return -1;
            }

            // Controllo disponibilità liquidità nel lato opposto dell'order book
            if (!OrderBook.hasLiquidity(type, size)) {
                System.err.println("[OrderManager] Market Order rifiutato: liquidità insufficiente per " +
                        PriceCalculator.formatSize(size) + " BTC " + type);
                return -1;
            }

            // Creazione e esecuzione immediata Market Order
            Order marketOrder = new Order(username, type, "market", size, 0, 0);
            allOrders.put(marketOrder.getOrderId(), marketOrder);

            System.out.println("[OrderManager] Esecuzione Market Order " + marketOrder.getOrderId() +
                    " - " + username + " " + type + " " + PriceCalculator.formatSize(size) + " BTC al prezzo di mercato");

            // Esecuzione immediata contro order book
            MatchingEngine.executeMarketOrder(marketOrder, OrderManager::executeTrade);

            return marketOrder.getOrderId();

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inserimento Market Order: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Inserisce un Stop Order nel sistema
     * Formato JSON secondo ALLEGATO 1: type=bid/ask, size=NUMBER, price=NUMBER (stopPrice)
     *
     * @param username utente proprietario dell'ordine
     * @param type "bid" per acquisto, "ask" per vendita
     * @param size quantità in millesimi di BTC
     * @param stopPrice prezzo di attivazione in millesimi di USD
     * @return orderId se successo, -1 se errore
     */
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
                        ": " + PriceCalculator.formatPrice(stopPrice) + " (prezzo corrente: " + PriceCalculator.formatPrice(currentMarketPrice) + ")");
                return -1;
            }

// Creazione Stop Order
            Order stopOrder = new Order(username, type, "stop", size, 0, stopPrice);
            allOrders.put(stopOrder.getOrderId(), stopOrder);
            StopOrderManager.addStopOrder(stopOrder);

            System.out.println("[OrderManager] Creato Stop Order " + stopOrder.getOrderId() +
                    " - " + username + " " + type + " " + PriceCalculator.formatSize(size) + " BTC @ stop " + PriceCalculator.formatPrice(stopPrice) + " USD");

            return stopOrder.getOrderId();

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inserimento Stop Order: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Cancella un ordine dal sistema
     * Formato JSON secondo ALLEGATO 1: orderId=NUMBER
     * Codici risposta: 100=OK, 101=errore
     *
     * @param username utente richiedente (per controllo ownership)
     * @param orderId ID dell'ordine da cancellare
     * @return 100 se successo, 101 se errore
     */
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
                System.err.println("[OrderManager] Cancel fallito: ordine " + orderId + " appartiene a " + order.getUsername());
                return 101;
            }

            // Controllo se ordine già eseguito completamente
            if (order.isFullyExecuted()) {
                System.err.println("[OrderManager] Cancel fallito: ordine " + orderId + " già completamente eseguito");
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

    // === STORICO PREZZI ===

    /**
     * Implementa getPriceHistory leggendo dal file StoricoOrdini.json unificato
     * Calcola OHLC (Open, High, Low, Close) per ogni giorno del mese richiesto
     *
     * @param monthYear formato "MMYYYY" (es: "012025" per gennaio 2025)
     * @return JsonObject con dati storici OHLC secondo specifiche ALLEGATO 1
     */
    public static JsonObject getPriceHistory(String monthYear) {
        try {
            // Validazione formato input
            if (monthYear == null || monthYear.length() != 6) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Formato mese non valido. Usare MMYYYY (es: 012025)");
                return error;
            }

            JsonArray allRecords = TradePersistence.loadTrades();
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
                error.addProperty("error", "Formato mese non valido: " + e.getMessage());
                return error;
            }

            // Carica tutti i record dal file
            JsonArray records = TradePersistence.loadTrades();

            if (allRecords.size() == 0) {
                JsonObject response = new JsonObject();
                response.addProperty("month", monthYear);
                response.addProperty("totalDays", 0);
                response.add("priceHistory", new JsonArray());
                response.addProperty("message", "Nessun record trovato nel file ");
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
                    // Converti timestamp UNIX a LocalDate (GMT come da specifiche)
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

            List<String> sortedDates = tradesByDay.keySet().stream()
                    .sorted()
                    .collect(Collectors.toList());

            // Calcola OHLC per ogni giorno usando PriceCalculator
            for (Map.Entry<String, List<JsonObject>> dayEntry : tradesByDay.entrySet()) {
                String date = dayEntry.getKey();
                List<JsonObject> dayTrades = dayEntry.getValue();

                // Usa PriceCalculator per calcolare OHLC del giorno
                JsonObject dayData = PriceCalculator.calculateDayOHLC(dayTrades, date);
                historyData.add(dayData);
            }

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

    // === MATCHING ENGINE ===


    /**
     * Esegue un singolo trade tra due ordini
     * Aggiorna prezzo di mercato, salva nel database e invia notifiche
     *
     * @param bidOrder ordine di acquisto
     * @param askOrder ordine di vendita
     * @param tradeSize quantità scambiata
     * @param executionPrice prezzo di esecuzione
     */
    private static void executeTrade(Order bidOrder, Order askOrder, int tradeSize, int executionPrice) {
        try {
            System.out.println("[OrderManager] TRADE ESEGUITO: " +
                    PriceCalculator.formatSize(tradeSize) + " BTC @ " + PriceCalculator.formatPrice(executionPrice) + " USD " +
                    "(Bid: " + bidOrder.getOrderId() + ", Ask: " + askOrder.getOrderId() + ")");

            // Aggiorna prezzo di mercato con il prezzo dell'ultimo trade
            int oldPrice = currentMarketPrice;
            currentMarketPrice = executionPrice;

            // Log cambio prezzo se significativo
            if (oldPrice != currentMarketPrice) {
                System.out.println("[OrderManager] Prezzo aggiornato: " + PriceCalculator.formatPrice(oldPrice) + " → " + PriceCalculator.formatPrice(currentMarketPrice) + " USD");

                // Chiama servizio multicast per controllo soglie utenti (vecchio ordinamento)
                PriceNotificationService.checkAndNotifyPriceThresholds(currentMarketPrice);
            }

            // Controlla e attiva eventuali Stop Orders
            StopOrderManager.checkAndActivateStopOrders(currentMarketPrice,
                    stopOrder -> MatchingEngine.executeMarketOrder(stopOrder, OrderManager::executeTrade));

            try {
                int tradeId = OrderIdGenerator.getNextOrderId();
                TradePersistence.saveExecutedTrade(bidOrder, askOrder, tradeSize, executionPrice, tradeId);
            } catch (IOException e) {
                System.err.println("[OrderManager] Errore salvataggio trade: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore esecuzione trade: " + e.getMessage());
        }
    }


    // === HELPER METHODS - VALIDAZIONE ===

    private static boolean isValidOrderType(String type) {
        return "bid".equals(type) || "ask".equals(type);
    }

    /**
     * Ottiene il prezzo corrente di mercato BTC
     *
     * @return prezzo corrente in millesimi USD
     */
    public static int getCurrentMarketPrice() {
        return currentMarketPrice;
    }

    /**
     * Metodo pubblico per eseguire Market Orders (usato da StopOrderManager)
     *
     * @param marketOrder ordine da eseguire come market order
     */
    public static void executeMarketOrder(Order marketOrder) {
        MatchingEngine.executeMarketOrder(marketOrder, OrderManager::executeTrade);
    }

}