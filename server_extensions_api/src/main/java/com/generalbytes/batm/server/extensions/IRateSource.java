/*************************************************************************************
 * Copyright (C) 2014-2016 GENERAL BYTES s.r.o. All rights reserved.
 *
 * This software may be distributed and modified under the terms of the GNU
 * General Public License version 2 (GPL2) as published by the Free Software
 * Foundation and appearing in the file GPL2.TXT included in the packaging of
 * this file. Please note that GPL2 Section 2[b] requires that all works based
 * on this software must also be made publicly available under the terms of
 * the GPL2 ("Copyleft").
 *
 * Contact information
 * -------------------
 *
 * GENERAL BYTES s.r.o.
 * Web      :  http://www.generalbytes.com
 *
 ************************************************************************************/
import com.generalbytes.batm.server.extensions.IExchangeAdvanced;
import com.generalbytes.batm.server.extensions.IRateSourceAdvanced;
import com.generalbytes.batm.server.extensions.ITask;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.bittrex.BittrexExchange;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
@@ -15,9 +14,7 @@
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.dto.trade.OpenOrders;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.service.account.AccountService;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.trade.TradeService;
@@ -33,7 +30,8 @@
    private static final Logger log = LoggerFactory.getLogger("batm.master.CoinassetExchange");

    private static final Set<String> FIAT_CURRENCIES = new HashSet<>();
    private static final Set<String> CRYPTO_CURRENCIES = new HashSet<>();
    public static final Comparator<LimitOrder> asksComparator = Comparator.comparing(LimitOrder::getLimitPrice);
    public static final Comparator<LimitOrder> bidsComparator = Comparator.comparing(LimitOrder::getLimitPrice).reversed();

    private Exchange exchange = null;
    private String apiKey;
@@ -49,7 +47,6 @@

    private static void initConstants() {
        FIAT_CURRENCIES.add(Currencies.USD);
        FIAT_CURRENCIES.add(Currencies.THB);
        CRYPTO_CURRENCIES.add(Currencies.BTC);
    }

    private synchronized Exchange getExchange() {
@@ -66,6 +63,7 @@ private synchronized Exchange getExchange() {
    public Set<String> getCryptoCurrencies() {
        Set<String> cryptoCurrencies = new HashSet<String>();
        cryptoCurrencies.add(Currencies.HBX);
        return cryptoCurrencies;
@@ -162,65 +160,39 @@ public String sellCoins(BigDecimal cryptoAmount, String cryptoCurrency, String f
        }

        log.info("Calling Bittrex exchange (sell " + cryptoAmount + " " + cryptoCurrency + ")");
        AccountService accountService = getExchange().getAccountService();
        TradeService tradeService = getExchange().getTradeService();
        MarketDataService marketDataService = getExchange().getMarketDataService();

        try {
            log.debug("AccountInfo as String: " + accountService.getAccountInfo().toString());

            CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrencyToUse);

            MarketOrder order = new MarketOrder(Order.OrderType.ASK, cryptoAmount, currencyPair);
            log.debug("marketOrder = " + order);
            OrderBook orderBook = marketDataService.getOrderBook(currencyPair);
            List<LimitOrder> bids = orderBook.getBids();
            log.debug("bids.size(): {}", bids.size());

            Collections.sort(bids, bidsComparator);

            LimitOrder order = new LimitOrder(Order.OrderType.ASK, cryptoAmount, currencyPair,
                "", null, getTradablePrice(cryptoAmount, bids));

            log.debug("order: {}", order);
            DDOSUtils.waitForPossibleCall(getClass());
            String orderId = tradeService.placeMarketOrder(order);
            log.debug("orderId = " + orderId + " " + order);
            String orderId = tradeService.placeLimitOrder(order);
            log.debug("orderId: {}", orderId);

            try {
                Thread.sleep(2000); //give exchange 2 seconds to reflect open order in order book
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            sleep(2000); //give exchange 2 seconds to reflect open order in order book

            // get open orders
            log.debug("Open orders:");
            boolean orderProcessed = false;
            int numberOfChecks = 0;
            while (!orderProcessed && numberOfChecks < 10) {
                boolean orderFound = false;
                DDOSUtils.waitForPossibleCall(getClass());
                OpenOrders openOrders = tradeService.getOpenOrders();
                for (LimitOrder openOrder : openOrders.getOpenOrders()) {
                    log.debug("openOrder = " + openOrder);
                    if (orderId.equals(openOrder.getId())) {
                        orderFound = true;
                        break;
                    }
                }
                if (orderFound) {
                    log.debug("Waiting for order to be processed.");
                    try {
                        Thread.sleep(3000); //don't get your ip address banned
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }else{
                    orderProcessed = true;
                }
                numberOfChecks++;
            }
            if (orderProcessed) {
            if (waitForOrderProcessed(tradeService, orderId, 10)) {
                return orderId;
            }
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Coinasset exchange (sellCoins) failed with message: " + e.getMessage());
            log.error("Coinasset exchange (sellCoins) failed", e);
        }
        return null;
    }

    @Override
    public String purchaseCoins(BigDecimal amount, String cryptoCurrency, String fiatCurrencyToUse, String description) {
    public String purchaseCoins(BigDecimal cryptoAmount, String cryptoCurrency, String fiatCurrencyToUse, String description) {
        if (!getCryptoCurrencies().contains(cryptoCurrency)) {
            log.error("Bittrex implementation supports only " + Arrays.toString(getCryptoCurrencies().toArray()));
            return null;
@@ -230,64 +202,81 @@ public String purchaseCoins(BigDecimal amount, String cryptoCurrency, String fia
            return null;
        }

        log.info("Calling Coinasset exchange (purchase " + amount + " " + cryptoCurrency + ")");
        AccountService accountService = getExchange().getAccountService();
        log.info("Calling Coinasset exchange (purchase " + cryptoAmount + " " + cryptoCurrency + ")");
        TradeService tradeService = getExchange().getTradeService();
        MarketDataService marketDataService = getExchange().getMarketDataService();

        try {
            log.debug("AccountInfo as String: " + accountService.getAccountInfo().toString());

            CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrencyToUse);

            MarketOrder order = new MarketOrder(Order.OrderType.BID, amount, currencyPair);
            log.debug("marketOrder = " + order);
            OrderBook orderBook = marketDataService.getOrderBook(currencyPair);
            List<LimitOrder> asks = orderBook.getAsks();

            Collections.sort(asks, asksComparator);

            LimitOrder limitOrder = new LimitOrder(Order.OrderType.BID, cryptoAmount, currencyPair, "", null, getTradablePrice(cryptoAmount, asks));
            log.debug("limitOrder = {}", limitOrder);
            DDOSUtils.waitForPossibleCall(getClass());
            String orderId = tradeService.placeMarketOrder(order);
            log.debug("orderId = " + orderId + " " + order);
            String orderId = tradeService.placeLimitOrder(limitOrder);
            log.debug("orderId = {}", orderId);

            try {
                Thread.sleep(2000); //give exchange 2 seconds to reflect open order in order book
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            sleep(2000); //give exchange 2 seconds to reflect open order in order book

            // get open orders
            log.debug("Open orders:");
            boolean orderProcessed = false;
            int numberOfChecks = 0;
            while (!orderProcessed && numberOfChecks < 10) {
                boolean orderFound = false;
                OpenOrders openOrders = tradeService.getOpenOrders();
                DDOSUtils.waitForPossibleCall(getClass());
                for (LimitOrder openOrder : openOrders.getOpenOrders()) {
                    log.debug("openOrder = " + openOrder);
                    if (orderId.equals(openOrder.getId())) {
                        orderFound = true;
                        break;
                    }
                }
                if (orderFound) {
                    log.debug("Waiting for order to be processed.");
                    try {
                        Thread.sleep(3000); //don't get your ip address banned
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }else{
                    orderProcessed = true;
                }
                numberOfChecks++;
            }
            if (orderProcessed) {
            if (waitForOrderProcessed(tradeService, orderId, 10)) {
                return orderId;
            }
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Coinasset exchange (purchaseCoins) failed with message: " + e.getMessage());
            log.error("Coinasset exchange (purchaseCoins) failed", e);
        }
        return null;
    }

    /**
     *
     * @param cryptoAmount
     * @param bidsOrAsksSorted bids: highest first, asks: lowest first
     * @return
     * @throws IOException when tradable price not found, e.g orderbook not received or too small.
     */
    private BigDecimal getTradablePrice(BigDecimal cryptoAmount, List<LimitOrder> bidsOrAsksSorted) throws IOException {
        BigDecimal total = BigDecimal.ZERO;

        for (LimitOrder order : bidsOrAsksSorted) {
            total = total.add(order.getOriginalAmount());
            if (cryptoAmount.compareTo(total) <= 0) {
                log.debug("tradablePrice: {}", order.getLimitPrice());
                return order.getLimitPrice();
            }
        }
        throw new IOException("Coinasset TradablePrice not available");
    }

    /**
     * wait for order to be processed
     * @param tradeService
     * @param orderId
     * @param maxTries
     * @return true if order was processed
     * @throws IOException
     */
    private boolean waitForOrderProcessed(TradeService tradeService, String orderId, int maxTries) throws IOException {
        boolean orderProcessed = false;
        int numberOfChecks = 0;
        while (!orderProcessed && numberOfChecks < maxTries) {
            DDOSUtils.waitForPossibleCall(getClass());
            OpenOrders openOrders = tradeService.getOpenOrders(tradeService.createOpenOrdersParams());
            boolean orderFound = openOrders.getOpenOrders().stream().map(LimitOrder::getId).anyMatch(orderId::equals);
            if (orderFound) {
                log.debug("Waiting for order to be processed.");
                sleep(3000); //don't get your ip address banned
            } else {
                orderProcessed = true;
            }
            numberOfChecks++;
        }
        return orderProcessed;
    }

    @Override
    public String getDepositAddress(String cryptoCurrency) {
        if (!getCryptoCurrencies().contains(cryptoCurrency)) {
@@ -331,35 +320,18 @@ public BigDecimal calculateBuyPrice(String cryptoCurrency, String fiatCurrency,
        DDOSUtils.waitForPossibleCall(getClass());
        MarketDataService marketDataService = getExchange().getMarketDataService();
        try {
            CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrency);
            DDOSUtils.waitForPossibleCall(getClass());
            OrderBook orderBook = marketDataService.getOrderBook(currencyPair);
            List<LimitOrder> asks = orderBook.getAsks();
            BigDecimal targetAmount = cryptoAmount;
            BigDecimal asksTotal = BigDecimal.ZERO;
            BigDecimal tradableLimit = null;
            Collections.sort(asks, new Comparator<LimitOrder>() {
                @Override
                public int compare(LimitOrder lhs, LimitOrder rhs) {
                    return lhs.getLimitPrice().compareTo(rhs.getLimitPrice());
                }
            });
            for (LimitOrder ask : asks) {
                asksTotal = asksTotal.add(ask.getTradableAmount());
                if (targetAmount.compareTo(asksTotal) <= 0) {
                    tradableLimit = ask.getLimitPrice();
                    break;
                }
            }

            CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrency);
            List<LimitOrder> asks = marketDataService.getOrderBook(currencyPair).getAsks();
            Collections.sort(asks, asksComparator);

            BigDecimal tradableLimit = getTradablePrice(cryptoAmount, asks);

            if (tradableLimit != null) {
                log.debug("Called Coinasset exchange for BUY rate: " + cryptoCurrency + fiatCurrency + " = " + tradableLimit);
                log.debug("Called Coinasset exchange for BUY rate: {}{} = {}", cryptoCurrency, fiatCurrency, tradableLimit);
                return tradableLimit.multiply(cryptoAmount);
            }
        } catch (ExchangeException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Throwable e) {
            e.printStackTrace();
        }
@@ -374,35 +346,16 @@ public BigDecimal calculateSellPrice(String cryptoCurrency, String fiatCurrency,
            CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrency);
            DDOSUtils.waitForPossibleCall(getClass());
            OrderBook orderBook = marketDataService.getOrderBook(currencyPair);
            List<LimitOrder> bids = orderBook.getBids();

            BigDecimal targetAmount = cryptoAmount;
            BigDecimal bidsTotal = BigDecimal.ZERO;
            BigDecimal tradableLimit = null;

            Collections.sort(bids, new Comparator<LimitOrder>() {
                @Override
                public int compare(LimitOrder lhs, LimitOrder rhs) {
                    return rhs.getLimitPrice().compareTo(lhs.getLimitPrice());
                }
            });
            List<LimitOrder> bids = orderBook.getBids();
            Collections.sort(bids, bidsComparator);

            for (LimitOrder bid : bids) {
                bidsTotal = bidsTotal.add(bid.getTradableAmount());
                if (targetAmount.compareTo(bidsTotal) <= 0) {
                    tradableLimit = bid.getLimitPrice();
                    break;
                }
            }
            BigDecimal tradableLimit = getTradablePrice(cryptoAmount, bids);

            if (tradableLimit != null) {
                log.debug("Called Coinasset exchange for SELL rate: " + cryptoCurrency + fiatCurrency + " = " + tradableLimit);
                log.debug("Called Coinasset exchange for SELL rate: {}{} = {}", cryptoCurrency, fiatCurrency, tradableLimit);
                return tradableLimit.multiply(cryptoAmount);
            }
        } catch (ExchangeException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Throwable e) {
            e.printStackTrace();
        }
@@ -500,25 +453,26 @@ public ITask createSellCoinsTask(BigDecimal amount, String cryptoCurrency, Strin
        @Override
        public boolean onCreate() {
            log.info("Calling Coinasset exchange (purchase " + amount + " " + cryptoCurrency + ")");
            AccountService accountService = getExchange().getAccountService();
            TradeService tradeService = getExchange().getTradeService();
            MarketDataService marketDataService = getExchange().getMarketDataService();

            try {
                log.debug("AccountInfo as String: " + accountService.getAccountInfo().toString());

                CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrencyToUse);

                MarketOrder order = new MarketOrder(Order.OrderType.BID, amount, currencyPair);
                log.debug("marketOrder = " + order);
                OrderBook orderBook = marketDataService.getOrderBook(currencyPair);
                List<LimitOrder> asks = orderBook.getAsks();

                Collections.sort(asks, asksComparator);

                LimitOrder order = new LimitOrder(Order.OrderType.BID, amount, currencyPair, "", null,
                    getTradablePrice(amount, asks));

                log.debug("order: {}", order);
                DDOSUtils.waitForPossibleCall(getClass());
                orderId = tradeService.placeMarketOrder(order);
                orderId = tradeService.placeLimitOrder(order);
                log.debug("orderId = " + orderId + " " + order);

                try {
                    Thread.sleep(2000); //give exchange 2 seconds to reflect open order in order book
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                sleep(2000); //give exchange 2 seconds to reflect open order in order book
            } catch (IOException e) {
                e.printStackTrace();
                log.error("Coinasset exchange (purchaseCoins) failed with message: " + e.getMessage());
@@ -624,25 +578,27 @@ public long getShortestTimeForNexStepInvocation() {
        @Override
        public boolean onCreate() {
            log.info("Calling Coinasset exchange (sell " + cryptoAmount + " " + cryptoCurrency + ")");
            AccountService accountService = getExchange().getAccountService();
            TradeService tradeService = getExchange().getTradeService();
            MarketDataService marketDataService = getExchange().getMarketDataService();

            try {
                log.debug("AccountInfo as String: " + accountService.getAccountInfo().toString());

                CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrencyToUse);

                MarketOrder order = new MarketOrder(Order.OrderType.ASK, cryptoAmount, currencyPair);
                log.debug("marketOrder = " + order);
                OrderBook orderBook = marketDataService.getOrderBook(currencyPair);
                List<LimitOrder> bids = orderBook.getBids();
                log.debug("bids.size(): {}", bids.size());

                Collections.sort(bids, bidsComparator);

                LimitOrder order = new LimitOrder(Order.OrderType.ASK, cryptoAmount, currencyPair,
                    "", null, getTradablePrice(cryptoAmount, bids));

                log.debug("order: {}", order);
                DDOSUtils.waitForPossibleCall(getClass());
                orderId = tradeService.placeMarketOrder(order);
                log.debug("orderId = " + orderId + " " + order);
                orderId = tradeService.placeLimitOrder(order);
                log.debug("orderId: {}", orderId);

                try {
                    Thread.sleep(2000); //give exchange 2 seconds to reflect open order in order book
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                sleep(2000); //give exchange 2 seconds to reflect open order in order book
            } catch (IOException e) {
                e.printStackTrace();
                log.error("Coinasset exchange (sellCoins) failed with message: " + e.getMessage());
@@ -725,4 +681,17 @@ public long getShortestTimeForNexStepInvocation() {
            return 5 * 1000; //it doesn't make sense to run step sooner than after 5 seconds
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            log.info("", e);
        }
    }

//    public static void main(String[] args) {
//        System.out.println(new BittrexExchange("XXX", "XXX")
//            .purchaseCoins(new BigDecimal(50), "BCH", "USD", "desc"));
//    }
}
   
13  ...om/generalbytes/batm/server/extensions/extra/bitcoin/exchanges/hitbtc/HitbtcExchange.java
@@ -7,6 +7,7 @@
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.account.Wallet;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@@ -39,6 +40,11 @@ private static ExchangeSpecification getSpecification(String clientKey, String c
        cryptoCurrencies.add(Currencies.HBX);
        return cryptoCurrencies;
    }

@@ -59,4 +65,11 @@ protected double getAllowedCallsPerSecond() {
        return 10;
    }

//    public static void main(String[] args) {
//        CoinassetExchange xch = new CoinassetExchange("THB", "USD");
//        System.out.println(xch.getDepositAddress("HBX"));
//        System.out.println(xch.getExchangeRateForSell("HBX", "USD", "THB"));
//        //System.out.println(xch.sellCoins(BigDecimal.TEN, "HBX", "USD", "THB"));
//        System.out.println(xch.sendCoins("86didNu7QQdJvm1CAxpUCy9rJr7AcRLdz1xzSMEFio8DVknAu3PoLkY7VNoDBFdM2ZZ4kzfKyrHEUHrjRauXwSZGJ7SA7Ki", BigDecimal.TEN, "XMR", ""));
//    }
} 
