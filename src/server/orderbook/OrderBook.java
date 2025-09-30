package server.orderbook;

import server.OrderManager.Order;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

//Gestisce le strutture dati dell'Order Book del sistema CROSS
public class OrderBook {

    // Ordini BID (acquisto) organizzati per prezzo
    // Key: prezzo, Value: lista ordinata per tempo (FIFO)
    private static final Map<Integer, LinkedList<Order>> bidOrders = new ConcurrentHashMap<>();

    // Ordini ASK (vendita) organizzati per prezzo
    // Key: prezzo, Value: lista ordinata per tempo (FIFO)
    private static final Map<Integer, LinkedList<Order>> askOrders = new ConcurrentHashMap<>();

    //Aggiunge un ordine BID all'order book mantenendo ordinamento prezzo/tempo
    public static void addToBidBook(Order order) {
        bidOrders.computeIfAbsent(order.getPrice(), k -> new LinkedList<>()).addLast(order);

        System.out.println("[OrderBook] Aggiunto BID: " + order.getOrderId() +
                " @ prezzo " + order.getPrice() + " (totale bid levels: " + bidOrders.size() + ")");
    }

    //Aggiunge un ordine ASK all'order book mantenendo ordinamento prezzo/tempo
    public static void addToAskBook(Order order) {
        askOrders.computeIfAbsent(order.getPrice(), k -> new LinkedList<>()).addLast(order);

        System.out.println("[OrderBook] Aggiunto ASK: " + order.getOrderId() +
                " @ prezzo " + order.getPrice() + " (totale ask levels: " + askOrders.size() + ")");
    }


    //Rimuove un ordine dall'order book appropriato (bid o ask)
    public static boolean removeOrder(Order order) {
        Map<Integer, LinkedList<Order>> book = "bid".equals(order.getType()) ? bidOrders : askOrders;
        LinkedList<Order> ordersAtPrice = book.get(order.getPrice());

        if (ordersAtPrice != null) {
            boolean removed = ordersAtPrice.remove(order);

            // Pulisci livello di prezzo se vuoto
            if (ordersAtPrice.isEmpty()) {
                book.remove(order.getPrice());
                System.out.println("[OrderBook] Rimosso livello prezzo " + order.getPrice() +
                        " per tipo " + order.getType());
            }

            if (removed) {
                System.out.println("[OrderBook] Rimosso ordine " + order.getOrderId() +
                        " dal book " + order.getType().toUpperCase());
            }

            return removed;
        }

        System.err.println("[OrderBook] Tentativo rimozione ordine " + order.getOrderId() +
                " fallito: livello prezzo " + order.getPrice() + " non trovato");
        return false;
    }

    //Rimuove il primo ordine da un livello di prezzo specifico
    public static Order removeFirstOrderAtPrice(int price, boolean isBid) {
        Map<Integer, LinkedList<Order>> book = isBid ? bidOrders : askOrders;
        LinkedList<Order> ordersAtPrice = book.get(price);

        if (ordersAtPrice != null && !ordersAtPrice.isEmpty()) {
            Order removedOrder = ordersAtPrice.removeFirst();

            // Pulisci livello se vuoto
            if (ordersAtPrice.isEmpty()) {
                book.remove(price);
                System.out.println("[OrderBook] Rimosso livello prezzo " + price +
                        " per tipo " + (isBid ? "BID" : "ASK"));
            }

            return removedOrder;
        }

        return null;
    }

    //Ottiene il miglior prezzo BID (più alto)
    public static Integer getBestBidPrice() {
        return bidOrders.keySet().stream().max(Integer::compareTo).orElse(null);
    }

    //Ottiene il miglior prezzo ASK (più basso)
    public static Integer getBestAskPrice() {
        return askOrders.keySet().stream().min(Integer::compareTo).orElse(null);
    }

    //Ottiene la lista di ordini per un prezzo specifico (bid)
    public static LinkedList<Order> getBidOrdersAtPrice(int price) {
        return bidOrders.get(price);
    }

    //Ottiene la lista di ordini per un prezzo specifico (ask)
    public static LinkedList<Order> getAskOrdersAtPrice(int price) {
        return askOrders.get(price);
    }

    //Ottiene tutti i livelli di prezzo BID ordinati (decrescente)
    public static List<Integer> getAllBidPrices() {
        return bidOrders.keySet().stream()
                .sorted((a, b) -> Integer.compare(b, a)) // Decrescente
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    //Ottiene tutti i livelli di prezzo ASK ordinati (crescente)
    public static List<Integer> getAllAskPrices() {
        return askOrders.keySet().stream()
                .sorted(Integer::compareTo) // Crescente
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    //Verifica se c'è abbastanza liquidità per eseguire un Market Order
    public static boolean hasLiquidity(String orderType, int requiredSize) {
        // Market buy ha bisogno di ask orders, market sell ha bisogno di bid orders
        Map<Integer, LinkedList<Order>> bookToCheck = "bid".equals(orderType) ? askOrders : bidOrders;

        int availableLiquidity = bookToCheck.values().stream()
                .flatMap(List::stream)
                .mapToInt(Order::getRemainingSize)
                .sum();

        boolean hasEnough = availableLiquidity >= requiredSize;

        if (!hasEnough) {
            System.out.println("[OrderBook] Liquidità insufficiente: richiesti " + requiredSize +
                    ", disponibili " + availableLiquidity + " per " + orderType);
        }

        return hasEnough;
    }

    //Calcola il volume totale disponibile per un tipo di ordine
    public static int getTotalVolumeAvailable(String orderType) {
        Map<Integer, LinkedList<Order>> bookToCheck = "bid".equals(orderType) ? askOrders : bidOrders;

        return bookToCheck.values().stream()
                .flatMap(List::stream)
                .mapToInt(Order::getRemainingSize)
                .sum();
    }


    //Ottiene statistiche generali dell'order book
    public static Map<String, Object> getOrderBookStats() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("bidLevels", bidOrders.size());
        stats.put("askLevels", askOrders.size());
        stats.put("totalBidOrders", bidOrders.values().stream().mapToInt(List::size).sum());
        stats.put("totalAskOrders", askOrders.values().stream().mapToInt(List::size).sum());
        stats.put("bestBid", getBestBidPrice());
        stats.put("bestAsk", getBestAskPrice());

        Integer bestBid = getBestBidPrice();
        Integer bestAsk = getBestAskPrice();
        if (bestBid != null && bestAsk != null) {
            stats.put("spread", bestAsk - bestBid);
        } else {
            stats.put("spread", null);
        }

        return stats;
    }

    //Stampa stato corrente dell'order book per debugging
    public static void printOrderBookSnapshot() {
        System.out.println("\n=== ORDER BOOK SNAPSHOT ===");

        // Mostra top 5 ASK levels (dal più basso al più alto)
        System.out.println("ASK (Vendita):");
        getAllAskPrices().stream()
                .limit(5)
                .forEach(price -> {
                    LinkedList<Order> orders = askOrders.get(price);
                    int totalSize = orders.stream().mapToInt(Order::getRemainingSize).sum();
                    System.out.println("  " + price + " -> " + totalSize + " (" + orders.size() + " ordini)");
                });

        // Spread
        Integer bestBid = getBestBidPrice();
        Integer bestAsk = getBestAskPrice();
        if (bestBid != null && bestAsk != null) {
            System.out.println("SPREAD: " + (bestAsk - bestBid));
        }

        // Mostra top 5 BID levels (dal più alto al più basso)
        System.out.println("BID (Acquisto):");
        getAllBidPrices().stream()
                .limit(5)
                .forEach(price -> {
                    LinkedList<Order> orders = bidOrders.get(price);
                    int totalSize = orders.stream().mapToInt(Order::getRemainingSize).sum();
                    System.out.println("  " + price + " -> " + totalSize + " (" + orders.size() + " ordini)");
                });

        System.out.println("========================\n");
    }

    //Accesso diretto al map bid orders (per matching engine)
    public static Map<Integer, LinkedList<Order>> getBidOrdersMap() {
        return bidOrders;
    }

    //Accesso diretto al map ask orders (per matching engine)
    public static Map<Integer, LinkedList<Order>> getAskOrdersMap() {
        return askOrders;
    }

    //Pulisce completamente l'order book (per testing/reset)
    public static void clearAll() {
        bidOrders.clear();
        askOrders.clear();
        System.out.println("[OrderBook] Order book completamente pulito");
    }

    //Verifica se l'order book è vuoto
    public static boolean isEmpty() {
        return bidOrders.isEmpty() && askOrders.isEmpty();
    }
}