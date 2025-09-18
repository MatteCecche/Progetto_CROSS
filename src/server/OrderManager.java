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
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Gestisce il sistema di trading e order book del sistema CROSS
 * Implementa il cuore dell'exchange per Bitcoin secondo le specifiche del progetto
 * - Order Book con algoritmo di matching price/time priority
 * - Gestione ordini: Limit Order, Market Order, Stop Order
 * - Matching engine per esecuzione automatica ordini
 * - Generazione orderId univoci e thread-safe
 * - Persistenza unificata in StoricoOrdini.json con formattazione
 * - Notifiche multicast per soglie prezzo (vecchio ordinamento)
 * - Calcolo OHLC per storico prezzi
 *
 * Utilizza strutture thread-safe per gestione concorrente di ordini multipli
 * Size e Price sono espressi in millesimi secondo specifiche:
 * - size 1000 = 1 BTC, price 58000000 = 58.000 USD
 */
public class OrderManager {

    // === CONFIGURAZIONE FILE UNIFICATO ===

    // File unico per tutti i trade/ordini storici
    private static final String STORICO_FILE = "data/StoricoOrdini.json";

    // Generatore thread-safe per orderId univoci
    private static final AtomicInteger orderIdGenerator = new AtomicInteger(1);

    // Lock per sincronizzazione accesso concorrente alle strutture dati
    private static final ReentrantReadWriteLock ordersLock = new ReentrantReadWriteLock();

    // Configurazione Gson per JSON formattato con indentazione (come da specifiche)
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()        // Abilita formattazione con spazi
            .disableHtmlEscaping()      // Non escape caratteri HTML
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

    // === GESTIONE FILE STORICO UNIFICATO ===

    /**
     * Carica tutti i trade/ordini dal file StoricoOrdini.json unificato
     * Struttura: { "trades": [ {...}, {...} ] }
     *
     * @return JsonArray contenente tutti i record dal file storico
     * @throws IOException se errori nella lettura del file
     */
    private static JsonArray loadStoricoOrdini() throws IOException {
        ordersLock.readLock().lock();
        try {
            File storicoFile = new File(STORICO_FILE);

            if (!storicoFile.exists()) {
                System.out.println("[OrderManager] File " + STORICO_FILE + " non trovato, creo struttura vuota");
                return new JsonArray();
            }

            try (FileReader reader = new FileReader(STORICO_FILE)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                if (root.has("trades") && root.get("trades").isJsonArray()) {
                    JsonArray trades = root.getAsJsonArray("trades");
                    return trades;
                } else {
                    System.out.println("[OrderManager] Struttura 'trades' non trovata, creo array vuoto");
                    return new JsonArray();
                }

            } catch (Exception e) {
                System.err.println("[OrderManager] Errore parsing " + STORICO_FILE + ": " + e.getMessage());
                return new JsonArray();
            }

        } finally {
            ordersLock.readLock().unlock();
        }
    }

    /**
     * Salva tutti i record nel file StoricoOrdini.json con formattazione pulita
     * Mantiene la struttura: { "trades": [ ... ] }
     * Il JSON sarà formattato con indentazione (come da specifiche)
     *
     * @param trades JsonArray con tutti i record
     * @throws IOException se errori nella scrittura
     */
    private static void saveStoricoOrdini(JsonArray trades) throws IOException {
        ordersLock.writeLock().lock();
        try {
            // Struttura unificata del file con formattazione
            JsonObject root = new JsonObject();
            root.add("trades", trades);

            // Scrivi il file con formattazione pretty
            try (FileWriter writer = new FileWriter(STORICO_FILE)) {
                gson.toJson(root, writer);
                writer.flush(); // Assicura che tutto sia scritto
            }

            System.out.println("[OrderManager] " + trades.size() + " record salvati in " + STORICO_FILE + " (formattato)");

        } finally {
            ordersLock.writeLock().unlock();
        }
    }

    /**
     * Aggiunge un nuovo trade/ordine al file storico
     * Chiamato ogni volta che un trade viene eseguito
     *
     * @param tradeData JsonObject contenente i dati del trade
     */
    private static void addTradeToStorico(JsonObject tradeData) {
        try {
            // Carica tutti i record esistenti
            JsonArray allRecords = loadStoricoOrdini();

            // Aggiunge il nuovo record
            allRecords.add(tradeData);

            // Salva tutto con formattazione
            saveStoricoOrdini(allRecords);

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore aggiunta trade a storico: " + e.getMessage());
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

        // Verifica/crea solo il file StoricoOrdini.json
        File storicoFile = new File(STORICO_FILE);
        if (storicoFile.exists()) {
            System.out.println("[OrderManager] ✅ Trovato file storico: " + STORICO_FILE);
            System.out.println("[OrderManager] Dimensione file: " + storicoFile.length() + " bytes");

            // Test rapido per verificare la struttura
            try {
                JsonArray records = loadStoricoOrdini();
                System.out.println("[OrderManager] ✅ File valido con " + records.size() + " record");

                if (records.size() > 0) {
                    // Mostra range temporale dei dati
                    JsonObject firstRecord = records.get(0).getAsJsonObject();
                    JsonObject lastRecord = records.get(records.size() - 1).getAsJsonObject();

                    if (firstRecord.has("timestamp") && lastRecord.has("timestamp")) {
                        long firstTimestamp = firstRecord.get("timestamp").getAsLong();
                        long lastTimestamp = lastRecord.get("timestamp").getAsLong();

                        LocalDate firstDate = Instant.ofEpochSecond(firstTimestamp)
                                .atZone(ZoneId.of("GMT")).toLocalDate();
                        LocalDate lastDate = Instant.ofEpochSecond(lastTimestamp)
                                .atZone(ZoneId.of("GMT")).toLocalDate();

                        System.out.println("[OrderManager] Range date: " + firstDate + " → " + lastDate);
                    }
                }
            } catch (Exception e) {
                System.err.println("[OrderManager] ⚠️ Errore lettura storico: " + e.getMessage());
            }
        } else {
            // Crea file vuoto con struttura corretta
            System.out.println("[OrderManager] Creazione nuovo file " + STORICO_FILE);
            JsonArray emptyTrades = new JsonArray();
            saveStoricoOrdini(emptyTrades);
            System.out.println("[OrderManager] ✅ File storico creato");
        }

        // Inizializza il generatore di orderID dal file esistente
        try {
            JsonArray records = loadStoricoOrdini();
            int maxOrderId = 0;

            for (int i = 0; i < records.size(); i++) {
                JsonObject record = records.get(i).getAsJsonObject();
                if (record.has("orderId")) {
                    int orderId = record.get("orderId").getAsInt();
                    if (orderId > maxOrderId) {
                        maxOrderId = orderId;
                    }
                }
            }

            // Imposta il prossimo ID disponibile
            orderIdGenerator.set(maxOrderId + 1);
            System.out.println("[OrderManager] Prossimo orderID: " + orderIdGenerator.get());

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore inizializzazione orderID generator: " + e.getMessage());
        }

        System.out.println("[OrderManager] Sistema gestione ordini inizializzato (solo StoricoOrdini.json)");
        System.out.println("[OrderManager] Prezzo corrente BTC: " + formatPrice(currentMarketPrice) + " USD");
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

            // Carica tutti i record dal file storico unificato
            JsonArray allRecords = loadStoricoOrdini();

            if (allRecords.size() == 0) {
                JsonObject response = new JsonObject();
                response.addProperty("month", monthYear);
                response.addProperty("totalDays", 0);
                response.add("priceHistory", new JsonArray());
                response.addProperty("message", "Nessun record trovato nel file " + STORICO_FILE);
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

            for (String date : sortedDates) {
                List<JsonObject> dayTrades = tradesByDay.get(date);

                if (dayTrades.isEmpty()) continue;

                // Ordina trade del giorno per timestamp per calcolo corretto OHLC
                dayTrades.sort((t1, t2) ->
                        Long.compare(t1.get("timestamp").getAsLong(), t2.get("timestamp").getAsLong()));

                // Calcola OHLC (Open, High, Low, Close)
                int openPrice = dayTrades.get(0).get("price").getAsInt(); // Primo trade del giorno
                int closePrice = dayTrades.get(dayTrades.size() - 1).get("price").getAsInt(); // Ultimo trade

                int highPrice = dayTrades.stream()
                        .mapToInt(trade -> trade.get("price").getAsInt())
                        .max()
                        .orElse(openPrice);

                int lowPrice = dayTrades.stream()
                        .mapToInt(trade -> trade.get("price").getAsInt())
                        .min()
                        .orElse(openPrice);

                // Calcola volume totale del giorno
                int totalVolume = dayTrades.stream()
                        .mapToInt(trade -> trade.get("size").getAsInt())
                        .sum();

                // Statistiche per tipo di trade
                long bidTrades = dayTrades.stream()
                        .filter(trade -> trade.has("type") && "bid".equals(trade.get("type").getAsString()))
                        .count();

                long askTrades = dayTrades.stream()
                        .filter(trade -> trade.has("type") && "ask".equals(trade.get("type").getAsString()))
                        .count();

                // Crea oggetto giornaliero secondo specifiche ALLEGATO 1
                JsonObject dayData = new JsonObject();
                dayData.addProperty("date", date);
                dayData.addProperty("openPrice", openPrice);   // Prezzo di apertura
                dayData.addProperty("highPrice", highPrice);   // Prezzo massimo
                dayData.addProperty("lowPrice", lowPrice);     // Prezzo minimo
                dayData.addProperty("closePrice", closePrice); // Prezzo di chiusura
                dayData.addProperty("volume", totalVolume);
                dayData.addProperty("tradesCount", dayTrades.size());
                dayData.addProperty("bidTrades", (int)bidTrades);
                dayData.addProperty("askTrades", (int)askTrades);

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
     * Esegue il matching tra ordini bid e ask secondo algoritmo price/time priority
     * Chiamato dopo ogni inserimento di Limit Order
     */
    private static void performMatching() {
        // Ottieni miglior prezzo bid e ask
        Integer bestBidPrice = getBestBidPrice();
        Integer bestAskPrice = getBestAskPrice();

        // Matching possibile solo se bid >= ask
        while (bestBidPrice != null && bestAskPrice != null && bestBidPrice >= bestAskPrice) {
            LinkedList<Order> bidList = bidOrders.get(bestBidPrice);
            LinkedList<Order> askList = askOrders.get(bestAskPrice);

            if (bidList == null || bidList.isEmpty() || askList == null || askList.isEmpty()) {
                break;
            }

            Order bidOrder = bidList.peekFirst();
            Order askOrder = askList.peekFirst();

            if (bidOrder == null || askOrder == null) {
                break;
            }

            // Calcola quantità da scambiare
            int tradeSize = Math.min(bidOrder.getRemainingSize(), askOrder.getRemainingSize());
            int executionPrice = bestAskPrice; // Price priority: prezzo dell'ordine più vecchio

            // Esegui il trade
            executeTrade(bidOrder, askOrder, tradeSize, executionPrice);

            // Aggiorna remaining size
            bidOrder.setRemainingSize(bidOrder.getRemainingSize() - tradeSize);
            askOrder.setRemainingSize(askOrder.getRemainingSize() - tradeSize);

            // Rimuovi ordini completamente eseguiti
            if (bidOrder.isFullyExecuted()) {
                bidList.removeFirst();
                if (bidList.isEmpty()) {
                    bidOrders.remove(bestBidPrice);
                }
            }

            if (askOrder.isFullyExecuted()) {
                askList.removeFirst();
                if (askList.isEmpty()) {
                    askOrders.remove(bestAskPrice);
                }
            }

            // Aggiorna prezzi migliori per prossima iterazione
            bestBidPrice = getBestBidPrice();
            bestAskPrice = getBestAskPrice();
        }
    }

    /**
     * Esegue un Market Order contro l'order book esistente
     * Consuma liquidità dal lato opposto fino a completamento o esaurimento liquidità
     *
     * @param marketOrder ordine di mercato da eseguire
     */
    private static void executeMarketOrder(Order marketOrder) {
        String oppositeType = "bid".equals(marketOrder.getType()) ? "ask" : "bid";
        Map<Integer, LinkedList<Order>> oppositeBook = "ask".equals(oppositeType) ? askOrders : bidOrders;

        int remainingSize = marketOrder.getRemainingSize();

        // Ordina prezzi: crescente per ask (compra al prezzo più basso), decrescente per bid (vendi al prezzo più alto)
        List<Integer> sortedPrices = oppositeBook.keySet().stream()
                .sorted("ask".equals(oppositeType) ? Integer::compareTo : (a, b) -> Integer.compare(b, a))
                .collect(Collectors.toList());

        for (Integer price : sortedPrices) {
            if (remainingSize <= 0) break;

            LinkedList<Order> orders = oppositeBook.get(price);
            if (orders == null || orders.isEmpty()) continue;

            Iterator<Order> iterator = orders.iterator();
            while (iterator.hasNext() && remainingSize > 0) {
                Order oppositeOrder = iterator.next();

                int tradeSize = Math.min(remainingSize, oppositeOrder.getRemainingSize());

                // Esegui trade al prezzo dell'ordine limite esistente
                executeTrade(
                        "bid".equals(marketOrder.getType()) ? marketOrder : oppositeOrder,
                        "ask".equals(marketOrder.getType()) ? marketOrder : oppositeOrder,
                        tradeSize,
                        price
                );

                remainingSize -= tradeSize;
                oppositeOrder.setRemainingSize(oppositeOrder.getRemainingSize() - tradeSize);

                // Rimuovi ordine se completamente eseguito
                if (oppositeOrder.isFullyExecuted()) {
                    iterator.remove();
                }
            }

            // Rimuovi livello di prezzo se vuoto
            if (orders.isEmpty()) {
                oppositeBook.remove(price);
            }
        }

        // Aggiorna remaining size del market order
        marketOrder.setRemainingSize(remainingSize);

        if (remainingSize > 0) {
            System.out.println("[OrderManager] Market Order " + marketOrder.getOrderId() +
                    " parzialmente eseguito. Rimanenti: " + formatSize(remainingSize) + " BTC");
        }
    }

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
                    formatSize(tradeSize) + " BTC @ " + formatPrice(executionPrice) + " USD " +
                    "(Bid: " + bidOrder.getOrderId() + ", Ask: " + askOrder.getOrderId() + ")");

            // Aggiorna prezzo di mercato con il prezzo dell'ultimo trade
            int oldPrice = currentMarketPrice;
            currentMarketPrice = executionPrice;

            // Log cambio prezzo se significativo
            if (oldPrice != currentMarketPrice) {
                System.out.println("[OrderManager] Prezzo aggiornato: " + formatPrice(oldPrice) + " → " + formatPrice(currentMarketPrice) + " USD");

                // Chiama servizio multicast per controllo soglie utenti (vecchio ordinamento)
                PriceNotificationService.checkAndNotifyPriceThresholds(currentMarketPrice);
            }

            // Controlla e attiva eventuali Stop Orders
            checkStopOrders();

            // Salva trade per storico
            saveExecutedTrade(bidOrder, askOrder, tradeSize, executionPrice);

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore esecuzione trade: " + e.getMessage());
        }
    }

    /**
     * Controlla e attiva Stop Orders quando condizioni sono soddisfatte
     * Chiamato ad ogni cambio di prezzo di mercato
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

    // === HELPER METHODS - ORDER BOOK ===

    /**
     * Aggiunge un ordine BID all'order book mantenendo ordinamento prezzo/tempo
     */
    private static void addToBidBook(Order order) {
        bidOrders.computeIfAbsent(order.getPrice(), k -> new LinkedList<>()).addLast(order);
    }

    /**
     * Aggiunge un ordine ASK all'order book mantenendo ordinamento prezzo/tempo
     */
    private static void addToAskBook(Order order) {
        askOrders.computeIfAbsent(order.getPrice(), k -> new LinkedList<>()).addLast(order);
    }

    /**
     * Rimuove un ordine dall'order book appropriato
     */
    private static boolean removeFromOrderBook(Order order) {
        Map<Integer, LinkedList<Order>> book = "bid".equals(order.getType()) ? bidOrders : askOrders;
        LinkedList<Order> orders = book.get(order.getPrice());

        if (orders != null) {
            boolean removed = orders.remove(order);
            if (orders.isEmpty()) {
                book.remove(order.getPrice());
            }

            // Rimuovi anche da stop orders se applicabile
            stopOrders.remove(order.getOrderId());

            return removed;
        }

        return false;
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
        Map<Integer, LinkedList<Order>> bookToCheck = "bid".equals(type) ? askOrders : bidOrders;

        int availableLiquidity = bookToCheck.values().stream()
                .flatMap(List::stream)
                .mapToInt(Order::getRemainingSize)
                .sum();

        return availableLiquidity >= requiredSize;
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

    /**
     * Ottiene il prezzo corrente di mercato BTC
     *
     * @return prezzo corrente in millesimi USD
     */
    public static int getCurrentMarketPrice() {
        return currentMarketPrice;
    }

    // === PERSISTENZA TRADE ===

    /**
     * Salva un trade eseguito direttamente nel file StoricoOrdini.json
     * Mantiene lo stesso formato dei tuoi dati esistenti con formattazione JSON
     *
     * @param bidOrder ordine di acquisto
     * @param askOrder ordine di vendita
     * @param size quantità scambiata
     * @param price prezzo di esecuzione
     */
    private static void saveExecutedTrade(Order bidOrder, Order askOrder, int size, int price) {
        try {
            // Crea il record del trade eseguito nel formato del tuo file
            JsonObject tradeRecord = new JsonObject();

            // Usa un ID unico per il trade
            tradeRecord.addProperty("orderId", orderIdGenerator.getAndIncrement());

            // Indica che è un trade eseguito
            tradeRecord.addProperty("type", "executed");
            tradeRecord.addProperty("orderType", "completed");

            // Dati principali del trade
            tradeRecord.addProperty("size", size);
            tradeRecord.addProperty("price", price);
            tradeRecord.addProperty("timestamp", System.currentTimeMillis() / 1000); // UNIX timestamp in secondi

            // Metadati aggiuntivi per tracciabilità
            tradeRecord.addProperty("bidOrderId", bidOrder.getOrderId());
            tradeRecord.addProperty("askOrderId", askOrder.getOrderId());
            tradeRecord.addProperty("bidUsername", bidOrder.getUsername());
            tradeRecord.addProperty("askUsername", askOrder.getUsername());

            // Aggiunge direttamente al file storico con formattazione
            addTradeToStorico(tradeRecord);

            System.out.println("[OrderManager] Trade salvato in " + STORICO_FILE + ": " +
                    formatSize(size) + " BTC @ " + formatPrice(price) + " USD (ID: " +
                    tradeRecord.get("orderId").getAsInt() + ")");

        } catch (Exception e) {
            System.err.println("[OrderManager] Errore salvataggio trade: " + e.getMessage());
        }
    }
}