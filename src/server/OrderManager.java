package server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Gestisce il sistema di trading e order book del sistema CROSS
 * Implementa il cuore dell'exchange per Bitcoin secondo le specifiche del progetto
 * - Order Book con algoritmo di matching price/time priority
 * - Gestione ordini: Limit Order, Market Order, Stop Order
 * - Matching engine per esecuzione automatica ordini
 * - Generazione orderId univoci e thread-safe
 * - Persistenza ordini eseguiti in formato JSON
 * - Notifiche multicast per soglie prezzo (vecchio ordinamento)
 * - Notifiche per ordini finalizzati
 *
 * Utilizza strutture thread-safe per gestione concorrente di ordini multipli
 * Size e Price sono espressi in millesimi secondo specifiche:
 * - size 1000 = 1 BTC, price 58000000 = 58.000 USD
 */
public class OrderManager {

    // Generatore thread-safe per orderId univoci
    private static final AtomicInteger orderIdGenerator = new AtomicInteger(1);

    // File per persistenza ordini eseguiti
    private static final String ORDERS_FILE = "data/orders.json";

    // Lock per sincronizzazione accesso concorrente alle strutture dati
    private static final ReentrantReadWriteLock ordersLock = new ReentrantReadWriteLock();

    // Configurazione Gson per pretty printing JSON
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    // === ORDER BOOK STRUCTURES (Thread-Safe) ===

    // Ordini BID (acquisto) ordinati per prezzo decrescente, poi per timestamp
    // TreeMap mantiene ordinamento automatico, LinkedList per time priority
    private static final Map<Integer, LinkedList<Order>> bidOrders = new ConcurrentHashMap<>();

    // Ordini ASK (vendita) ordinati per prezzo crescente, poi per timestamp
    private static final Map<Integer, LinkedList<Order>> askOrders = new ConcurrentHashMap<>();

    // Stop Orders in attesa del trigger (monitorati ad ogni trade)
    private static final Map<Integer, Order> stopOrders = new ConcurrentHashMap<>();

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
            this.orderId = orderIdGenerator.getAndIncrement();
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

    /**
     * Inizializza il sistema di gestione ordini
     * Configura filesystem e strutture dati necessarie
     *
     * @throws IOException se errori nell'inizializzazione del filesystem
     */
    public static void initialize() throws IOException {
        // Crea directory data se non esiste
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            System.out.println("[OrderManager] Creata directory data/");
        }

        // Crea file ordini vuoto se non esiste
        File ordersFile = new File(ORDERS_FILE);
        if (!ordersFile.exists()) {
            initializeOrdersFile();
            System.out.println("[OrderManager] Inizializzato file orders.json");
        }

        System.out.println("[OrderManager] Sistema gestione ordini inizializzato");
        System.out.println("[OrderManager] Prezzo corrente BTC: " + formatPrice(currentMarketPrice) + " USD");
    }

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
                    " - " + username + " " + type + " " + formatSize(size) + " BTC @ " + formatPrice(price) + " USD");

            // Inserimento nell'order book appropriato
            if ("bid".equals(type)) {
                addToBidBook(order);
            } else {
                addToAskBook(order);
            }

            // Tentativo di matching immediato
            performMatching();

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
            if (!hasLiquidity(type, size)) {
                System.err.println("[OrderManager] Market Order rifiutato: liquidità insufficiente per " +
                        formatSize(size) + " BTC " + type);
                return -1;
            }

            // Creazione e esecuzione immediata Market Order
            Order marketOrder = new Order(username, type, "market", size, 0, 0);
            allOrders.put(marketOrder.getOrderId(), marketOrder);

            System.out.println("[OrderManager] Esecuzione Market Order " + marketOrder.getOrderId() +
                    " - " + username + " " + type + " " + formatSize(size) + " BTC al prezzo di mercato");

            // Esecuzione immediata contro order book
            executeMarketOrder(marketOrder);

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
            if (!isValidStopPrice(type, stopPrice)) {
                System.err.println("[OrderManager] Stop Price non valido per " + type +
                        ": " + formatPrice(stopPrice) + " (prezzo corrente: " + formatPrice(currentMarketPrice) + ")");
                return -1;
            }

            // Creazione Stop Order
            Order stopOrder = new Order(username, type, "stop", size, 0, stopPrice);
            allOrders.put(stopOrder.getOrderId(), stopOrder);
            stopOrders.put(stopOrder.getOrderId(), stopOrder);

            System.out.println("[OrderManager] Creato Stop Order " + stopOrder.getOrderId() +
                    " - " + username + " " + type + " " + formatSize(size) + " BTC @ stop " + formatPrice(stopPrice) + " USD");

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
            boolean removed = removeFromOrderBook(order);
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

    // === HELPER METHODS - VALIDAZIONE ===

    private static boolean isValidOrderType(String type) {
        return "bid".equals(type) || "ask".equals(type);
    }

    private static boolean isValidStopPrice(String type, int stopPrice) {
        // Stop Buy: attivato quando prezzo >= stopPrice (protezione short position)
        // Stop Sell: attivato quando prezzo <= stopPrice (stop loss)
        if ("bid".equals(type)) {
            return stopPrice >= currentMarketPrice; // Stop buy sopra mercato
        } else {
            return stopPrice <= currentMarketPrice; // Stop sell sotto mercato
        }
    }

    private static boolean hasLiquidity(String type, int requiredSize) {
        // Market buy ha bisogno di ask orders, market sell ha bisogno di bid orders
        Map<Integer, LinkedList<Order>> bookToCheck = "bid".equals(type) ?
                askOrders : bidOrders;

        int availableSize = 0;
        for (LinkedList<Order> ordersAtPrice : bookToCheck.values()) {
            for (Order order : ordersAtPrice) {
                availableSize += order.getRemainingSize();
                if (availableSize >= requiredSize) {
                    return true;
                }
            }
        }
        return false;
    }

    // === ORDER BOOK MANAGEMENT ===

    private static void addToBidBook(Order order) {
        bidOrders.computeIfAbsent(order.getPrice(), k -> new LinkedList<>()).addLast(order);
    }

    private static void addToAskBook(Order order) {
        askOrders.computeIfAbsent(order.getPrice(), k -> new LinkedList<>()).addLast(order);
    }

    private static boolean removeFromOrderBook(Order order) {
        try {
            if ("stop".equals(order.getOrderType())) {
                return stopOrders.remove(order.getOrderId()) != null;
            }

            Map<Integer, LinkedList<Order>> book = "bid".equals(order.getType()) ?
                    bidOrders : askOrders;
            LinkedList<Order> ordersAtPrice = book.get(order.getPrice());

            if (ordersAtPrice != null) {
                boolean removed = ordersAtPrice.remove(order);
                if (ordersAtPrice.isEmpty()) {
                    book.remove(order.getPrice());
                }
                return removed;
            }
            return false;

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore rimozione dall'order book: " + e.getMessage());
            return false;
        }
    }

    // === MATCHING ENGINE ===

    /**
     * Esegue l'algoritmo di matching price/time priority
     * Chiamato dopo ogni inserimento di Limit Order
     */
    private static void performMatching() {
        // Ottieni best bid e best ask
        Integer bestBidPrice = getBestBidPrice();
        Integer bestAskPrice = getBestAskPrice();

        // Matching possibile solo se bid >= ask (spread crossing)
        while (bestBidPrice != null && bestAskPrice != null && bestBidPrice >= bestAskPrice) {

            LinkedList<Order> bidOrdersAtPrice = bidOrders.get(bestBidPrice);
            LinkedList<Order> askOrdersAtPrice = askOrders.get(bestAskPrice);

            if (bidOrdersAtPrice.isEmpty() || askOrdersAtPrice.isEmpty()) {
                break;
            }

            // Time priority: primi ordini nella lista (più vecchi)
            Order bidOrder = bidOrdersAtPrice.getFirst();
            Order askOrder = askOrdersAtPrice.getFirst();

            // Esegui trade
            executeTrade(bidOrder, askOrder, bestAskPrice); // Price improvement per buyer

            // Rimuovi ordini completamente eseguiti
            if (bidOrder.isFullyExecuted()) {
                bidOrdersAtPrice.removeFirst();
                if (bidOrdersAtPrice.isEmpty()) {
                    bidOrders.remove(bestBidPrice);
                }
            }

            if (askOrder.isFullyExecuted()) {
                askOrdersAtPrice.removeFirst();
                if (askOrdersAtPrice.isEmpty()) {
                    askOrders.remove(bestAskPrice);
                }
            }

            // Ricalcola best prices per prossima iterazione
            bestBidPrice = getBestBidPrice();
            bestAskPrice = getBestAskPrice();
        }

        // Controlla stop orders dopo ogni matching
        checkStopOrders();
    }

    /**
     * Esegue un Market Order contro l'order book
     */
    private static void executeMarketOrder(Order marketOrder) {
        Map<Integer, LinkedList<Order>> oppositeBook = "bid".equals(marketOrder.getType()) ?
                askOrders : bidOrders;

        // Ordina prezzi per esecuzione ottimale
        List<Integer> sortedPrices = new ArrayList<>(oppositeBook.keySet());
        if ("bid".equals(marketOrder.getType())) {
            Collections.sort(sortedPrices); // Buy al prezzo più basso possibile
        } else {
            Collections.sort(sortedPrices, Collections.reverseOrder()); // Sell al prezzo più alto possibile
        }

        for (Integer price : sortedPrices) {
            if (marketOrder.isFullyExecuted()) break;

            LinkedList<Order> ordersAtPrice = oppositeBook.get(price);
            Iterator<Order> iterator = ordersAtPrice.iterator();

            while (iterator.hasNext() && !marketOrder.isFullyExecuted()) {
                Order limitOrder = iterator.next();

                executeTrade(
                        "bid".equals(marketOrder.getType()) ? marketOrder : limitOrder,
                        "ask".equals(marketOrder.getType()) ? marketOrder : limitOrder,
                        price
                );

                if (limitOrder.isFullyExecuted()) {
                    iterator.remove();
                }
            }

            if (ordersAtPrice.isEmpty()) {
                oppositeBook.remove(price);
            }
        }

        checkStopOrders();
    }

    /**
     * Esegue un singolo trade tra due ordini
     * ✅ INTEGRAZIONE MULTICAST: Notifica cambio prezzo per soglie (vecchio ordinamento)
     */
    private static void executeTrade(Order bidOrder, Order askOrder, int executionPrice) {
        try {
            int tradeSize = Math.min(bidOrder.getRemainingSize(), askOrder.getRemainingSize());

            bidOrder.setRemainingSize(bidOrder.getRemainingSize() - tradeSize);
            askOrder.setRemainingSize(askOrder.getRemainingSize() - tradeSize);

            // Salva prezzo precedente per controllo cambiamenti
            int oldPrice = currentMarketPrice;

            // Aggiorna prezzo di mercato corrente
            currentMarketPrice = executionPrice;

            System.out.println("[OrderManager] TRADE ESEGUITO: " + formatSize(tradeSize) + " BTC @ " + formatPrice(executionPrice) + " USD " +
                    "(" + bidOrder.getUsername() + " buy, " + askOrder.getUsername() + " sell)");

            // ✅ INTEGRAZIONE MULTICAST: Controllo soglie prezzo per notifiche multicast
            // Solo se il prezzo è effettivamente cambiato (evita notifiche duplicate)
            if (oldPrice != currentMarketPrice) {
                System.out.println("[OrderManager] Prezzo aggiornato: " + formatPrice(oldPrice) + " → " + formatPrice(currentMarketPrice) + " USD");

                // Chiama servizio multicast per controllo soglie utenti (vecchio ordinamento)
                PriceNotificationService.checkAndNotifyPriceThresholds(currentMarketPrice);
            }

            // Salva trade per storico (TODO: implementare persistenza trade)
            saveExecutedTrade(bidOrder, askOrder, tradeSize, executionPrice);

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore esecuzione trade: " + e.getMessage());
        }
    }

    /**
     * Controlla e attiva Stop Orders quando condizioni sono soddisfatte
     */
    private static void checkStopOrders() {
        List<Order> toActivate = new ArrayList<>();

        for (Order stopOrder : stopOrders.values()) {
            boolean shouldActivate = false;

            if ("bid".equals(stopOrder.getType()) && currentMarketPrice >= stopOrder.getStopPrice()) {
                shouldActivate = true; // Stop buy trigger
            } else if ("ask".equals(stopOrder.getType()) && currentMarketPrice <= stopOrder.getStopPrice()) {
                shouldActivate = true; // Stop sell trigger
            }

            if (shouldActivate) {
                toActivate.add(stopOrder);
            }
        }

        // Attiva stop orders come market orders
        for (Order stopOrder : toActivate) {
            stopOrders.remove(stopOrder.getOrderId());
            System.out.println("[OrderManager] STOP TRIGGER: Ordine " + stopOrder.getOrderId() +
                    " attivato come Market Order");

            // Esegui come market order
            executeMarketOrder(stopOrder);
        }
    }

    // === UTILITY METHODS ===

    private static Integer getBestBidPrice() {
        return bidOrders.keySet().stream().max(Integer::compareTo).orElse(null);
    }

    private static Integer getBestAskPrice() {
        return askOrders.keySet().stream().min(Integer::compareTo).orElse(null);
    }

    private static String formatPrice(int priceInMilliths) {
        return String.format("%,.0f", priceInMilliths / 1000.0);
    }

    private static String formatSize(int sizeInMilliths) {
        return String.format("%.3f", sizeInMilliths / 1000.0);
    }

    // === PERSISTENCE ===

    private static void initializeOrdersFile() throws IOException {
        JsonObject rootObject = new JsonObject();
        rootObject.add("executedTrades", new JsonArray());

        try (FileWriter writer = new FileWriter(ORDERS_FILE)) {
            gson.toJson(rootObject, writer);
        }
    }

    private static void saveExecutedTrade(Order bidOrder, Order askOrder, int size, int price) {
        // TODO: Implementare salvataggio trade per storico
        // Formato JSON da definire per persistenza
    }

    /**
     * Ottiene il prezzo corrente di mercato BTC
     *
     * @return prezzo corrente in millesimi USD
     */
    public static int getCurrentMarketPrice() {
        return currentMarketPrice;
    }
}