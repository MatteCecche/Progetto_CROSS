package server.orderbook;

import server.OrderManager.Order;
import server.utility.PriceCalculator;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementa l'algoritmo di matching per il sistema di trading CROSS
 * - PRICE Priority: ordini con prezzo migliore hanno precedenza
 * - BID: prezzi più alti hanno precedenza (massima offerta d'acquisto)
 * - ASK: prezzi più bassi hanno precedenza (minima richiesta di vendita)
 * - TIME Priority: a parità di prezzo, ordini più vecchi hanno precedenza
 * - MATCHING: esecuzione quando bestBid >= bestAsk
 */
public class MatchingEngine {

    // Lock statico per sincronizzare operazioni di matching
    private static final Object MATCHING_LOCK = new Object();

    //Interface per callback di esecuzione trade
    public interface TradeExecutor {
        //Esegue un trade tra due ordini
        void executeTrade(Order bidOrder, Order askOrder, int tradeSize, int executionPrice);
    }

    //Esegue il matching automatico tra ordini bid e ask nell'order book
    public static int performLimitOrderMatching(TradeExecutor tradeExecutor) {
        // Sincronizzazione completa dell'operazione di matching
        synchronized (MATCHING_LOCK) {
            int tradesExecuted = 0;

            System.out.println("[MatchingEngine] Inizio sessione matching automatico");

            // Continua matching finché possibile (bestBid >= bestAsk)
            while (true) {
                // Ottieni migliori prezzi correnti
                Integer bestBidPrice = OrderBook.getBestBidPrice();
                Integer bestAskPrice = OrderBook.getBestAskPrice();

                // Matching possibile solo se bid >= ask
                if (bestBidPrice == null || bestAskPrice == null || bestBidPrice < bestAskPrice) {
                    break; // Nessun matching possibile
                }

                // Ottieni liste ordini ai migliori prezzi
                LinkedList<Order> bidList = OrderBook.getBidOrdersAtPrice(bestBidPrice);
                LinkedList<Order> askList = OrderBook.getAskOrdersAtPrice(bestAskPrice);

                // Validazione liste non vuote
                if (bidList == null || bidList.isEmpty() || askList == null || askList.isEmpty()) {
                    break;
                }

                // Ottieni ordini più vecchi (TIME priority)
                Order bidOrder = bidList.getFirst();
                Order askOrder = askList.getFirst();

                // Calcola quantità da scambiare (minimo tra i due ordini)
                int tradeSize = Math.min(bidOrder.getRemainingSize(), askOrder.getRemainingSize());

                // Prezzo di esecuzione: prezzo dell'ordine più vecchio (limit order)
                // In questo caso usiamo bestAskPrice (ordine già nell'order book)
                int executionPrice = bestAskPrice;

                System.out.println("[MatchingEngine] Match trovato: " +
                        PriceCalculator.formatSize(tradeSize) + " BTC @ " +
                        PriceCalculator.formatPrice(executionPrice) + " USD");
                System.out.println("  BID: Order " + bidOrder.getOrderId() +
                        " (" + bidOrder.getUsername() + ")");
                System.out.println("  ASK: Order " + askOrder.getOrderId() +
                        " (" + askOrder.getUsername() + ")");

                // Esegui trade tramite callback
                if (tradeExecutor != null) {
                    tradeExecutor.executeTrade(bidOrder, askOrder, tradeSize, executionPrice);
                }

                tradesExecuted++;

                // Aggiorna remaining size degli ordini
                bidOrder.setRemainingSize(bidOrder.getRemainingSize() - tradeSize);
                askOrder.setRemainingSize(askOrder.getRemainingSize() - tradeSize);

                // Rimuovi ordini completamente eseguiti dalle liste
                if (bidOrder.isFullyExecuted()) {
                    bidList.removeFirst();
                    System.out.println("[MatchingEngine] Bid Order " + bidOrder.getOrderId() +
                            " completamente eseguito");

                    // Rimuovi livello prezzo se vuoto
                    if (bidList.isEmpty()) {
                        OrderBook.getBidOrdersMap().remove(bestBidPrice);
                        System.out.println("[MatchingEngine] Rimosso livello BID " + bestBidPrice);
                    }
                }

                if (askOrder.isFullyExecuted()) {
                    askList.removeFirst();
                    System.out.println("[MatchingEngine] Ask Order " + askOrder.getOrderId() +
                            " completamente eseguito");

                    // Rimuovi livello prezzo se vuoto
                    if (askList.isEmpty()) {
                        OrderBook.getAskOrdersMap().remove(bestAskPrice);
                        System.out.println("[MatchingEngine] Rimosso livello ASK " + bestAskPrice);
                    }
                }
            }

            if (tradesExecuted > 0) {
                System.out.println("[MatchingEngine] Sessione matching completata: " +
                        tradesExecuted + " trade eseguiti");
            }

            return tradesExecuted;
        } // Fine synchronized
    }

    //Esegue un Market Order contro l'order book esistente
    public static boolean executeMarketOrder(Order marketOrder, TradeExecutor tradeExecutor) {
        if (!"market".equals(marketOrder.getOrderType())) {
            throw new IllegalArgumentException("Solo Market Orders possono essere processati da executeMarketOrder()");
        }

        // Sincronizzazione per operazioni di Market Order
        synchronized (MATCHING_LOCK) {
            System.out.println("[MatchingEngine] Esecuzione Market Order " + marketOrder.getOrderId() +
                    " - " + marketOrder.getType().toUpperCase() + " " +
                    PriceCalculator.formatSize(marketOrder.getRemainingSize()) + " BTC");

            // Determina lato opposto dell'order book
            String oppositeType = "bid".equals(marketOrder.getType()) ? "ask" : "bid";
            Map<Integer, LinkedList<Order>> oppositeBook = "ask".equals(oppositeType) ?
                    OrderBook.getAskOrdersMap() : OrderBook.getBidOrdersMap();

            int remainingSize = marketOrder.getRemainingSize();
            int initialSize = remainingSize;

            // Ottieni prezzi ordinati ottimalmente per market order
            List<Integer> sortedPrices = getSortedPricesForMarketOrder(oppositeType, oppositeBook);

            // Consuma liquidità livello per livello
            for (Integer price : sortedPrices) {
                if (remainingSize <= 0) break;

                LinkedList<Order> ordersAtPrice = oppositeBook.get(price);
                if (ordersAtPrice == null || ordersAtPrice.isEmpty()) {
                    continue; // Salta livelli vuoti (race condition)
                }

                System.out.println("[MatchingEngine] Market Order consuma livello " +
                        PriceCalculator.formatPrice(price) + " USD (" + ordersAtPrice.size() + " ordini)");

                // Consuma tutti gli ordini a questo livello di prezzo
                Iterator<Order> iterator = ordersAtPrice.iterator();
                while (iterator.hasNext() && remainingSize > 0) {
                    Order oppositeOrder = iterator.next();

                    int tradeSize = Math.min(remainingSize, oppositeOrder.getRemainingSize());

                    // Determina ordini per callback (bid sempre primo parametro)
                    Order bidOrder = "bid".equals(marketOrder.getType()) ? marketOrder : oppositeOrder;
                    Order askOrder = "ask".equals(marketOrder.getType()) ? marketOrder : oppositeOrder;

                    System.out.println("[MatchingEngine] Market execution: " +
                            PriceCalculator.formatSize(tradeSize) + " BTC @ " +
                            PriceCalculator.formatPrice(price) + " USD vs Order " + oppositeOrder.getOrderId());

                    // Esegui trade al prezzo dell'ordine limite esistente
                    if (tradeExecutor != null) {
                        tradeExecutor.executeTrade(bidOrder, askOrder, tradeSize, price);
                    }

                    // Aggiorna quantità rimanenti
                    remainingSize -= tradeSize;
                    oppositeOrder.setRemainingSize(oppositeOrder.getRemainingSize() - tradeSize);

                    // Rimuovi ordine limite se completamente eseguito
                    if (oppositeOrder.isFullyExecuted()) {
                        iterator.remove();
                        System.out.println("[MatchingEngine] Limit Order " + oppositeOrder.getOrderId() +
                                " consumato da Market Order");
                    }
                }

                // Rimuovi livello di prezzo se vuoto
                if (ordersAtPrice.isEmpty()) {
                    oppositeBook.remove(price);
                    System.out.println("[MatchingEngine] Rimosso livello prezzo " + price +
                            " dopo consumo Market Order");
                }
            }

            // Aggiorna remaining size del market order
            marketOrder.setRemainingSize(remainingSize);

            // Risultati esecuzione
            int executedSize = initialSize - remainingSize;
            boolean fullyExecuted = remainingSize <= 0;

            System.out.println("[MatchingEngine] Market Order " + marketOrder.getOrderId() +
                    " - Eseguiti: " + PriceCalculator.formatSize(executedSize) + " BTC" +
                    (fullyExecuted ? " (COMPLETATO)" : " (PARZIALE - Rimasti: " +
                            PriceCalculator.formatSize(remainingSize) + " BTC)"));

            return fullyExecuted;
        } // Fine synchronized
    }

    //Ottiene i prezzi ordinati in modo ottimale per un Market Order
    private static List<Integer> getSortedPricesForMarketOrder(String oppositeType,
                                                               Map<Integer, LinkedList<Order>> oppositeBook) {

        List<Integer> prices = new ArrayList<Integer>(oppositeBook.keySet());

        if ("ask".equals(oppositeType)) {
            // Market BUY: consuma ASK dal prezzo più basso (miglior prezzo vendita)
            Collections.sort(prices);
        } else {
            // Market SELL: consuma BID dal prezzo più alto (miglior prezzo acquisto)
            Collections.sort(prices, Collections.reverseOrder());
        }

        return prices;
    }

    //Ottiene statistiche correnti del matching engine
    public static Map<String, Object> getMatchingEngineStats() {
        // CORREZIONE: Protezione lettura con synchronized per consistenza
        synchronized (MATCHING_LOCK) {
            Map<String, Object> stats = new HashMap<String, Object>();

            Integer bestBid = OrderBook.getBestBidPrice();
            Integer bestAsk = OrderBook.getBestAskPrice();

            stats.put("bestBid", bestBid);
            stats.put("bestAsk", bestAsk);

            // Calcola spread
            if (bestBid != null && bestAsk != null) {
                stats.put("spread", bestAsk - bestBid);
                stats.put("matchingPossible", bestBid >= bestAsk);
            } else {
                stats.put("spread", -1);
                stats.put("matchingPossible", false);
            }

            // Liquidità ai migliori prezzi
            if (bestBid != null) {
                LinkedList<Order> bidOrders = OrderBook.getBidOrdersAtPrice(bestBid);
                int bidDepth = bidOrders != null ?
                        bidOrders.stream().mapToInt(Order::getRemainingSize).sum() : 0;
                stats.put("bestBidDepth", bidDepth);
            }

            if (bestAsk != null) {
                LinkedList<Order> askOrders = OrderBook.getAskOrdersAtPrice(bestAsk);
                int askDepth = askOrders != null ?
                        askOrders.stream().mapToInt(Order::getRemainingSize).sum() : 0;
                stats.put("bestAskDepth", askDepth);
            }

            return stats;
        }
    }

    //Stampa stato corrente del matching engine
    public static void printMatchingEngineStatus() {
        System.out.println("\n=== MATCHING ENGINE STATUS ===");

        Map<String, Object> stats = getMatchingEngineStats();

        Integer bestBid = (Integer) stats.get("bestBid");
        Integer bestAsk = (Integer) stats.get("bestAsk");
        Integer spread = (Integer) stats.get("spread");
        Boolean matchingPossible = (Boolean) stats.get("matchingPossible");

        System.out.println("Best BID: " + (bestBid != null ?
                PriceCalculator.formatPrice(bestBid) + " USD" : "N/A"));
        System.out.println("Best ASK: " + (bestAsk != null ?
                PriceCalculator.formatPrice(bestAsk) + " USD" : "N/A"));
        System.out.println("Spread: " + (spread >= 0 ?
                PriceCalculator.formatPrice(spread) + " USD" : "N/A"));
        System.out.println("Matching possibile: " + (matchingPossible ? "SI" : "NO"));

        if (stats.containsKey("bestBidDepth")) {
            System.out.println("Liquidità BID @ best: " +
                    PriceCalculator.formatSize((Integer)stats.get("bestBidDepth")) + " BTC");
        }
        if (stats.containsKey("bestAskDepth")) {
            System.out.println("Liquidità ASK @ best: " +
                    PriceCalculator.formatSize((Integer)stats.get("bestAskDepth")) + " BTC");
        }

        System.out.println("==============================\n");
    }

    //Verifica se è possibile un matching immediato
    public static boolean canMatchImmediately() {
        Integer bestBid = OrderBook.getBestBidPrice();
        Integer bestAsk = OrderBook.getBestAskPrice();
        return bestBid != null && bestAsk != null && bestBid >= bestAsk;
    }

    //Calcola il volume totale matchabile ai prezzi correnti
    public static int calculateMatchableVolume() {
        synchronized (MATCHING_LOCK) {
            if (!canMatchImmediately()) {
                return 0;
            }

            Integer bestBid = OrderBook.getBestBidPrice();
            Integer bestAsk = OrderBook.getBestAskPrice();

            LinkedList<Order> bidOrders = OrderBook.getBidOrdersAtPrice(bestBid);
            LinkedList<Order> askOrders = OrderBook.getAskOrdersAtPrice(bestAsk);

            if (bidOrders == null || askOrders == null) {
                return 0;
            }

            int totalBidVolume = bidOrders.stream().mapToInt(Order::getRemainingSize).sum();
            int totalAskVolume = askOrders.stream().mapToInt(Order::getRemainingSize).sum();

            return Math.min(totalBidVolume, totalAskVolume);
        }
    }

    //Ottiene il numero di livelli di prezzo nell'order book
    public static Map<String, Integer> getPriceLevelStats() {
        synchronized (MATCHING_LOCK) {
            Map<String, Integer> stats = new HashMap<String, Integer>();
            stats.put("bidLevels", OrderBook.getBidOrdersMap().size());
            stats.put("askLevels", OrderBook.getAskOrdersMap().size());
            return stats;
        }
    }
}