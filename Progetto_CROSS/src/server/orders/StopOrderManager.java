package server.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import server.OrderManager.Order;
import server.utility.PriceCalculator;
import server.orderbook.MatchingEngine;

/*
 * Gestisce gli Stop Orders
 */
public class StopOrderManager {

    private static final Map<Integer, Order> stopOrders = new ConcurrentHashMap<>();

    public static void addStopOrder(Order stopOrder) {
        if (!"stop".equals(stopOrder.getOrderType())) {
            throw new IllegalArgumentException("[StopOrderManager] Solo ordini di tipo 'stop' possono essere aggiunti");
        }

        stopOrders.put(stopOrder.getOrderId(), stopOrder);
        System.out.println("[StopOrderManager] Aggiunto Stop Order " + stopOrder.getOrderId());
    }

    public static boolean removeStopOrder(int orderId) {
        Order removed = stopOrders.remove(orderId);

        if (removed != null) {
            System.out.println("[StopOrderManager] Rimosso Stop Order " + orderId);
            return true;
        }

        return false;
    }

    public static boolean isValidStopPrice(String orderType, int stopPrice, int currentMarketPrice) {
        return PriceCalculator.isValidStopPrice(orderType, stopPrice, currentMarketPrice);
    }

    public static void checkAndActivateStopOrders(int newMarketPrice, MatchingEngine.TradeExecutor tradeExecutor) {
        List<Order> toActivate = new ArrayList<>();

        for (Order stopOrder : stopOrders.values()) {
            if (shouldActivateStopOrder(stopOrder, newMarketPrice)) {
                toActivate.add(stopOrder);
            }
        }

        for (Order stopOrder : toActivate) {
            activateStopOrder(stopOrder, tradeExecutor);
        }

        if (!toActivate.isEmpty()) {
            System.out.println("[StopOrderManager] Attivati " + toActivate.size() + " Stop Orders");
        }
    }

    private static boolean shouldActivateStopOrder(Order stopOrder, int currentPrice) {
        String orderType = stopOrder.getType();
        int stopPrice = stopOrder.getStopPrice();

        if ("bid".equals(orderType)) {
            return currentPrice >= stopPrice;
        } else if ("ask".equals(orderType)) {
            return currentPrice <= stopPrice;
        }

        return false;
    }

    private static void activateStopOrder(Order stopOrder, MatchingEngine.TradeExecutor tradeExecutor) {
        stopOrders.remove(stopOrder.getOrderId());

        System.out.println("[StopOrderManager] STOP TRIGGER: Order " + stopOrder.getOrderId() + " attivato come Market Order");

        if (tradeExecutor != null) {
            MarketOrderManager.insertMarketOrder(stopOrder, tradeExecutor);
        } else {
            System.err.println("[StopOrderManager] ERRORE: TradeExecutor null per order " + stopOrder.getOrderId());
        }
    }
}