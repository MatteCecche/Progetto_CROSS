package server.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import server.OrderManager.Order;

/*
 * Implementa l'algoritmo di matching degli ordini
 */
public class MatchingEngine {

    // Lock statico per sincronizzare operazioni di matching
    private static final Object MATCHING_LOCK = new Object();

    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RESET = "\u001B[0m";

    public interface TradeExecutor {
        void executeTrade(Order bidOrder, Order askOrder, int tradeSize, int executionPrice);
    }

    public static void performLimitOrderMatching(TradeExecutor tradeExecutor) {
        synchronized (MATCHING_LOCK) {
            int tradesExecuted = 0;  // Lo tengo per i log

            while (true) {
                Integer bestBidPrice = OrderBook.getBestBidPrice();
                Integer bestAskPrice = OrderBook.getBestAskPrice();

                if (bestBidPrice == null || bestAskPrice == null || bestBidPrice < bestAskPrice) {
                    break;
                }

                LinkedList<Order> bidList = OrderBook.getBidOrdersAtPrice(bestBidPrice);
                LinkedList<Order> askList = OrderBook.getAskOrdersAtPrice(bestAskPrice);

                if (bidList == null || bidList.isEmpty() || askList == null || askList.isEmpty()) {
                    break;
                }

                Order bidOrder = bidList.getFirst();
                Order askOrder = askList.getFirst();

                int tradeSize = Math.min(bidOrder.getRemainingSize(), askOrder.getRemainingSize());
                int executionPrice = bestAskPrice;

                System.out.println(ANSI_GREEN + "[MatchingEngine] Match: Order " + bidOrder.getOrderId() + " <-> " + askOrder.getOrderId() + ANSI_RESET);

                if (tradeExecutor != null) {
                    tradeExecutor.executeTrade(bidOrder, askOrder, tradeSize, executionPrice);
                }

                tradesExecuted++;

                bidOrder.setRemainingSize(bidOrder.getRemainingSize() - tradeSize);
                askOrder.setRemainingSize(askOrder.getRemainingSize() - tradeSize);

                if (bidOrder.isFullyExecuted()) {
                    bidList.removeFirst();
                    if (bidList.isEmpty()) {
                        OrderBook.getBidOrdersMap().remove(bestBidPrice);
                    }
                }

                if (askOrder.isFullyExecuted()) {
                    askList.removeFirst();
                    if (askList.isEmpty()) {
                        OrderBook.getAskOrdersMap().remove(bestAskPrice);
                    }
                }
            }

            if (tradesExecuted > 0) {
                System.out.println(ANSI_GREEN + "[MatchingEngine] Eseguiti " + tradesExecuted + " trade" + ANSI_RESET);
            }
        }
    }

    public static boolean executeMarketOrder(Order marketOrder, TradeExecutor tradeExecutor) {
        if (!"market".equals(marketOrder.getOrderType())) {
            throw new IllegalArgumentException(ANSI_RED + "Solo Market Orders" + ANSI_RESET);
        }

        synchronized (MATCHING_LOCK) {
            System.out.println(ANSI_GREEN + "[MatchingEngine] Esecuzione Market Order " + marketOrder.getOrderId() + ANSI_RESET);

            String oppositeType = "bid".equals(marketOrder.getType()) ? "ask" : "bid";Map<Integer, LinkedList<Order>> oppositeBook = "ask".equals(oppositeType) ? OrderBook.getAskOrdersMap() : OrderBook.getBidOrdersMap();

            int remainingSize = marketOrder.getRemainingSize();
            int initialSize = remainingSize;

            List<Integer> sortedPrices = getSortedPricesForMarketOrder(oppositeType, oppositeBook);

            for (Integer price : sortedPrices) {
                if (remainingSize <= 0) break;

                LinkedList<Order> ordersAtPrice = oppositeBook.get(price);
                if (ordersAtPrice == null || ordersAtPrice.isEmpty()) {
                    continue;
                }

                Iterator<Order> iterator = ordersAtPrice.iterator();
                while (iterator.hasNext() && remainingSize > 0) {
                    Order oppositeOrder = iterator.next();

                    int tradeSize = Math.min(remainingSize, oppositeOrder.getRemainingSize());

                    Order bidOrder = "bid".equals(marketOrder.getType()) ? marketOrder : oppositeOrder;
                    Order askOrder = "ask".equals(marketOrder.getType()) ? marketOrder : oppositeOrder;

                    if (tradeExecutor != null) {
                        tradeExecutor.executeTrade(bidOrder, askOrder, tradeSize, price);
                    }

                    remainingSize -= tradeSize;
                    oppositeOrder.setRemainingSize(oppositeOrder.getRemainingSize() - tradeSize);

                    if (oppositeOrder.isFullyExecuted()) {
                        iterator.remove();
                    }
                }

                if (ordersAtPrice.isEmpty()) {
                    oppositeBook.remove(price);
                }
            }

            marketOrder.setRemainingSize(remainingSize);

            int executedSize = initialSize - remainingSize;
            boolean fullyExecuted = remainingSize <= 0;

            System.out.println(ANSI_GREEN + "[MatchingEngine] Market Order eseguito: " + executedSize + " di " + initialSize + (fullyExecuted ? " (completo)" : " (parziale)") + ANSI_RESET);

            return fullyExecuted;
        }
    }

    private static List<Integer> getSortedPricesForMarketOrder(String oppositeType, Map<Integer, LinkedList<Order>> oppositeBook) {
        List<Integer> prices = new ArrayList<>(oppositeBook.keySet());

        if ("ask".equals(oppositeType)) {
            prices.sort(null);
        } else {
            prices.sort(Collections.reverseOrder());
        }

        return prices;
    }
}