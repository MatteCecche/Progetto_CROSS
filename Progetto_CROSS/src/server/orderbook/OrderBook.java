package server.orderbook;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import server.OrderManager.Order;

/*
 * Gestisce le strutture dati dell'Order Book
 */
public class OrderBook {

    // Ordini BID (acquisto) organizzati per prezzo
    private static final Map<Integer, LinkedList<Order>> bidOrders = new ConcurrentHashMap<>();

    // Ordini ASK (vendita) organizzati per prezzo
    private static final Map<Integer, LinkedList<Order>> askOrders = new ConcurrentHashMap<>();

    public static void addToBidBook(Order order) {
        bidOrders.computeIfAbsent(order.getPrice(), _ -> new LinkedList<>()).addLast(order);
        System.out.println("[OrderBook] Aggiunto BID: " + order.getOrderId());
    }

    public static void addToAskBook(Order order) {
        askOrders.computeIfAbsent(order.getPrice(), _ -> new LinkedList<>()).addLast(order);
        System.out.println("[OrderBook] Aggiunto ASK: " + order.getOrderId());
    }

    public static boolean removeOrder(Order order) {
        Map<Integer, LinkedList<Order>> book = "bid".equals(order.getType()) ? bidOrders : askOrders;
        LinkedList<Order> ordersAtPrice = book.get(order.getPrice());

        if (ordersAtPrice != null) {
            boolean removed = ordersAtPrice.remove(order);

            if (ordersAtPrice.isEmpty()) {
                book.remove(order.getPrice());
            }

            if (removed) {
                System.out.println("[OrderBook] Rimosso ordine " + order.getOrderId());
            }

            return removed;
        }

        return false;
    }

    public static Integer getBestBidPrice() {

        return bidOrders.keySet().stream().max(Integer::compareTo).orElse(null);
    }

    public static Integer getBestAskPrice() {

        return askOrders.keySet().stream().min(Integer::compareTo).orElse(null);
    }

    public static LinkedList<Order> getBidOrdersAtPrice(int price) {

        return bidOrders.get(price);
    }

    public static LinkedList<Order> getAskOrdersAtPrice(int price) {

        return askOrders.get(price);
    }

    public static boolean hasLiquidity(String orderType, int requiredSize) {
        Map<Integer, LinkedList<Order>> bookToCheck = "bid".equals(orderType) ? askOrders : bidOrders;

        int availableLiquidity = bookToCheck.values().stream().flatMap(List::stream).mapToInt(Order::getRemainingSize).sum();

        return availableLiquidity >= requiredSize;
    }

    public static Map<Integer, LinkedList<Order>> getBidOrdersMap() {

        return bidOrders;
    }

    public static Map<Integer, LinkedList<Order>> getAskOrdersMap() {

        return askOrders;
    }
}