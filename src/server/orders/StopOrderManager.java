package server.orders;

import server.OrderManager.Order;
import server.utility.PriceCalculator;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestisce gli Stop Orders nel sistema CROSS
 *
 * Responsabilità:
 * - Memorizzazione Stop Orders in attesa di attivazione
 * - Monitoraggio prezzo di mercato per trigger conditions
 * - Attivazione automatica Stop Orders quando condizioni soddisfatte
 * - Validazione logica Stop Price vs prezzo corrente
 * - Conversione Stop Orders in Market Orders all'attivazione
 *
 * Logica Stop Orders:
 * - Stop BUY (bid): attivato quando prezzo >= stopPrice (compra quando sale)
 * - Stop SELL (ask): attivato quando prezzo <= stopPrice (vendi quando scende)
 *
 * Estratto da OrderManager per principio Single Responsibility
 * Thread-safe per uso in ambiente multithreaded del server
 */
public class StopOrderManager {

    // === STORAGE STOP ORDERS ===

    // Stop Orders in attesa del trigger (orderId -> Order)
    // Monitorati ad ogni cambio di prezzo di mercato
    private static final Map<Integer, Order> stopOrders = new ConcurrentHashMap<>();

    // === GESTIONE STOP ORDERS ===

    /**
     * Aggiunge un Stop Order al sistema di monitoraggio
     *
     * @param stopOrder ordine stop da monitorare
     */
    public static void addStopOrder(Order stopOrder) {
        if (!"stop".equals(stopOrder.getOrderType())) {
            throw new IllegalArgumentException("Solo ordini di tipo 'stop' possono essere aggiunti");
        }

        stopOrders.put(stopOrder.getOrderId(), stopOrder);

        System.out.println("[StopOrderManager] Aggiunto Stop Order " + stopOrder.getOrderId() +
                " per monitoraggio: " + stopOrder.getType().toUpperCase() +
                " @ stop " + PriceCalculator.formatPrice(stopOrder.getStopPrice()) + " USD");

        System.out.println("[StopOrderManager] Totale Stop Orders attivi: " + stopOrders.size());
    }

    /**
     * Rimuove un Stop Order dal sistema di monitoraggio
     *
     * @param orderId ID dell'ordine da rimuovere
     * @return true se rimosso, false se non presente
     */
    public static boolean removeStopOrder(int orderId) {
        Order removed = stopOrders.remove(orderId);

        if (removed != null) {
            System.out.println("[StopOrderManager] Rimosso Stop Order " + orderId + " dal monitoraggio");
            return true;
        }

        return false;
    }

    /**
     * Controlla se un orderId è un Stop Order attivo
     *
     * @param orderId ID dell'ordine da verificare
     * @return true se è uno Stop Order in attesa
     */
    public static boolean isActiveStopOrder(int orderId) {
        return stopOrders.containsKey(orderId);
    }

    /**
     * Ottiene un Stop Order specifico
     *
     * @param orderId ID dell'ordine
     * @return Order o null se non trovato
     */
    public static Order getStopOrder(int orderId) {
        return stopOrders.get(orderId);
    }

    /**
     * Ottiene tutti gli Stop Orders attivi (copia per sicurezza thread)
     *
     * @return mappa orderId -> Order di tutti gli stop orders attivi
     */
    public static Map<Integer, Order> getAllActiveStopOrders() {
        return new HashMap<>(stopOrders);
    }

    // === VALIDAZIONI ===

    /**
     * Determina se un prezzo di stop è logicamente valido
     * Usa PriceCalculator per la logica di validazione
     *
     * @param orderType "bid" per acquisto, "ask" per vendita
     * @param stopPrice prezzo di attivazione
     * @param currentMarketPrice prezzo corrente di mercato
     * @return true se stopPrice è logicamente corretto
     */
    public static boolean isValidStopPrice(String orderType, int stopPrice, int currentMarketPrice) {
        return PriceCalculator.isValidStopPrice(orderType, stopPrice, currentMarketPrice);
    }

    // === MONITORAGGIO E ATTIVAZIONE ===

    /**
     * Controlla tutti gli Stop Orders e attiva quelli che soddisfano le condizioni
     * Chiamato ad ogni cambio di prezzo di mercato dal sistema
     *
     * @param newMarketPrice nuovo prezzo di mercato
     * @param marketOrderExecutor callback per eseguire i market orders risultanti
     * @return lista di Stop Orders attivati
     */
    public static List<Order> checkAndActivateStopOrders(int newMarketPrice, MarketOrderExecutor marketOrderExecutor) {
        List<Order> activatedOrders = new ArrayList<>();

        // Identifica Stop Orders da attivare
        List<Order> toActivate = new ArrayList<>();

        for (Order stopOrder : stopOrders.values()) {
            if (shouldActivateStopOrder(stopOrder, newMarketPrice)) {
                toActivate.add(stopOrder);
            }
        }

        // Attiva tutti gli Stop Orders identificati
        for (Order stopOrder : toActivate) {
            activateStopOrder(stopOrder, marketOrderExecutor);
            activatedOrders.add(stopOrder);
        }

        if (!activatedOrders.isEmpty()) {
            System.out.println("[StopOrderManager] Attivati " + activatedOrders.size() +
                    " Stop Orders al prezzo " + PriceCalculator.formatPrice(newMarketPrice) + " USD");
        }

        return activatedOrders;
    }

    /**
     * Determina se un singolo Stop Order deve essere attivato
     *
     * @param stopOrder ordine da controllare
     * @param currentPrice prezzo corrente di mercato
     * @return true se deve essere attivato
     */
    private static boolean shouldActivateStopOrder(Order stopOrder, int currentPrice) {
        String orderType = stopOrder.getType();
        int stopPrice = stopOrder.getStopPrice();

        if ("bid".equals(orderType)) {
            // Stop BUY: attivato quando prezzo >= stopPrice
            // (compra quando il prezzo sale oltre la soglia)
            return currentPrice >= stopPrice;
        } else if ("ask".equals(orderType)) {
            // Stop SELL: attivato quando prezzo <= stopPrice
            // (vendi quando il prezzo scende sotto la soglia)
            return currentPrice <= stopPrice;
        }

        return false;
    }

    /**
     * Attiva un singolo Stop Order convertendolo in Market Order
     *
     * @param stopOrder ordine da attivare
     * @param marketOrderExecutor callback per eseguire il market order risultante
     */
    private static void activateStopOrder(Order stopOrder, MarketOrderExecutor marketOrderExecutor) {
        // Rimuovi dallo storage Stop Orders
        stopOrders.remove(stopOrder.getOrderId());

        System.out.println("[StopOrderManager] STOP TRIGGER: Ordine " + stopOrder.getOrderId() +
                " attivato come Market Order - " + stopOrder.getType().toUpperCase() +
                " " + PriceCalculator.formatSize(stopOrder.getRemainingSize()) + " BTC");

        // Esegui come Market Order tramite callback
        if (marketOrderExecutor != null) {
            marketOrderExecutor.executeMarketOrder(stopOrder);
        } else {
            System.err.println("[StopOrderManager] ERRORE: MarketOrderExecutor è null - " +
                    "impossibile eseguire Stop Order " + stopOrder.getOrderId());
        }
    }

    // === INTERFACE PER CALLBACK ===

    /**
     * Interface per callback di esecuzione Market Orders
     * Permette di disaccoppiare StopOrderManager dal sistema di execution
     */
    @FunctionalInterface
    public interface MarketOrderExecutor {
        /**
         * Esegue un Market Order derivato da Stop Order attivato
         *
         * @param stopOrderToExecute ordine stop da eseguire come market order
         */
        void executeMarketOrder(Order stopOrderToExecute);
    }

    // === STATISTICHE E MONITORAGGIO ===

    /**
     * Ottiene statistiche correnti sugli Stop Orders attivi
     *
     * @return mappa con statistiche di sistema
     */
    public static Map<String, Object> getStopOrderStats() {
        Map<String, Object> stats = new HashMap<>();

        int totalStopOrders = stopOrders.size();
        long bidStops = stopOrders.values().stream().filter(o -> "bid".equals(o.getType())).count();
        long askStops = stopOrders.values().stream().filter(o -> "ask".equals(o.getType())).count();

        stats.put("totalActiveStops", totalStopOrders);
        stats.put("bidStops", bidStops);
        stats.put("askStops", askStops);

        if (!stopOrders.isEmpty()) {
            OptionalInt minStopPrice = stopOrders.values().stream()
                    .mapToInt(Order::getStopPrice)
                    .min();
            OptionalInt maxStopPrice = stopOrders.values().stream()
                    .mapToInt(Order::getStopPrice)
                    .max();

            stats.put("minStopPrice", minStopPrice.isPresent() ? minStopPrice.getAsInt() : null);
            stats.put("maxStopPrice", maxStopPrice.isPresent() ? maxStopPrice.getAsInt() : null);
        }

        return stats;
    }

    /**
     * Stampa stato corrente degli Stop Orders per debugging
     */
    public static void printStopOrdersSnapshot() {
        System.out.println("\n=== STOP ORDERS SNAPSHOT ===");
        System.out.println("Totale Stop Orders attivi: " + stopOrders.size());

        if (stopOrders.isEmpty()) {
            System.out.println("Nessun Stop Order attivo");
        } else {
            System.out.println("Stop Orders per tipo:");

            // Raggruppa per tipo
            Map<String, List<Order>> byType = new HashMap<>();
            stopOrders.values().forEach(order ->
                    byType.computeIfAbsent(order.getType(), k -> new ArrayList<>()).add(order)
            );

            byType.forEach((type, orders) -> {
                System.out.println("  " + type.toUpperCase() + " (" + orders.size() + " ordini):");
                orders.stream()
                        .sorted(Comparator.comparing(Order::getStopPrice))
                        .limit(5)  // Mostra solo i primi 5
                        .forEach(order ->
                                System.out.println("    Order " + order.getOrderId() +
                                        " @ stop " + PriceCalculator.formatPrice(order.getStopPrice()) +
                                        " (" + PriceCalculator.formatSize(order.getRemainingSize()) + " BTC)")
                        );
            });
        }

        System.out.println("==========================\n");
    }

    /**
     * Ottiene lista Stop Orders ordinata per stop price
     * Utile per analisi e debugging
     *
     * @param orderType "bid" o "ask" per filtrare, null per tutti
     * @return lista ordinata di Stop Orders
     */
    public static List<Order> getStopOrdersByPrice(String orderType) {
        return stopOrders.values().stream()
                .filter(order -> orderType == null || orderType.equals(order.getType()))
                .sorted(Comparator.comparing(Order::getStopPrice))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    // === UTILITY ===

    /**
     * Conta il numero totale di Stop Orders attivi
     *
     * @return numero di stop orders in attesa di attivazione
     */
    public static int getActiveStopOrderCount() {
        return stopOrders.size();
    }

    /**
     * Verifica se ci sono Stop Orders attivi
     *
     * @return true se almeno un Stop Order è in attesa
     */
    public static boolean hasActiveStopOrders() {
        return !stopOrders.isEmpty();
    }

    /**
     * Pulisce tutti gli Stop Orders (per testing/reset)
     * DA USARE CON CAUTELA
     */
    public static void clearAllStopOrders() {
        int cleared = stopOrders.size();
        stopOrders.clear();
        System.out.println("[StopOrderManager] Puliti " + cleared + " Stop Orders");
    }
}
