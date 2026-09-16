package com.gillingteknik.piratio;

import android.app.Activity;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Google Play Billing client for consumable Pirat.io coin packs.
 *
 * Security boundary: this class never grants coins locally and never consumes purchases.
 * It forwards a PURCHASED token to CloudProfileClient; the server verifies the purchase
 * with Google Play, credits the authoritative wallet exactly once and consumes the token.
 */
public final class GooglePlayBilling implements PurchasesUpdatedListener {
    public interface Listener {
        void onBillingState(String message, boolean profileChanged);
    }

    public static final String COINS_1000 = "piratio_coins_1000";
    public static final String COINS_3000 = "piratio_coins_3000";
    public static final String COINS_8000 = "piratio_coins_8000";

    private static final Set<String> SUPPORTED_PRODUCTS = Set.of(COINS_1000, COINS_3000, COINS_8000);

    private final Activity activity;
    private final CloudProfileClient cloud;
    private final Listener listener;
    private final BillingClient billingClient;
    private final Set<String> submittingTokens = new HashSet<>();
    private boolean ready;
    private boolean closed;

    public GooglePlayBilling(Activity activity, CloudProfileClient cloud, Listener listener) {
        this.activity = activity;
        this.cloud = cloud;
        this.listener = listener;
        this.billingClient = BillingClient.newBuilder(activity)
                .setListener(this)
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build())
                .enableAutoServiceReconnection()
                .build();
        connect();
    }

    private void connect() {
        if (closed || ready) return;
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                ready = billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK;
                if (ready) {
                    notifyState("Google Play store ready", false);
                    refreshPurchases();
                } else {
                    notifyState("Google Play Billing unavailable: " + billingResult.getDebugMessage(), false);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                ready = false;
                notifyState("Google Play store disconnected", false);
            }
        });
    }

    public boolean isReady() {
        return ready;
    }

    public void buy(String productId) {
        if (!SUPPORTED_PRODUCTS.contains(productId)) {
            notifyState("Unknown coin pack", false);
            return;
        }
        if (!cloud.hasSession() || cloud.accountId().isEmpty()) {
            notifyState("Sign in with Play Games before buying coins", false);
            return;
        }
        if (!ready) {
            connect();
            notifyState("Connecting to Google Play…", false);
            return;
        }

        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product))
                .build();

        billingClient.queryProductDetailsAsync(params, this::onProductDetails);
    }

    private void onProductDetails(BillingResult result, QueryProductDetailsResult detailsResult) {
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            notifyState("Could not load coin pack: " + result.getDebugMessage(), false);
            return;
        }
        List<ProductDetails> details = detailsResult.getProductDetailsList();
        if (details == null || details.isEmpty()) {
            notifyState("Coin pack is not configured in Google Play yet", false);
            return;
        }

        ProductDetails product = details.get(0);
        BillingFlowParams.ProductDetailsParams.Builder productParams =
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product);

        List<ProductDetails.OneTimePurchaseOfferDetails> offers = product.getOneTimePurchaseOfferDetailsList();
        if (offers != null && !offers.isEmpty()) {
            String offerToken = offers.get(0).getOfferToken();
            if (offerToken != null && !offerToken.isEmpty()) productParams.setOfferToken(offerToken);
        }

        BillingFlowParams flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(productParams.build()))
                .setObfuscatedAccountId(cloud.accountId())
                .build();
        BillingResult launch = billingClient.launchBillingFlow(activity, flow);
        if (launch.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            notifyState("Could not start purchase: " + launch.getDebugMessage(), false);
        }
    }

    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            notifyState("Purchase cancelled", false);
            return;
        }
        if (code != BillingClient.BillingResponseCode.OK) {
            notifyState("Purchase failed: " + result.getDebugMessage(), false);
            return;
        }
        processPurchases(purchases);
    }

    public void refreshPurchases() {
        if (!ready || closed) return;
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        billingClient.queryPurchasesAsync(params, (result, purchases) -> {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases);
            }
        });
    }

    private void processPurchases(List<Purchase> purchases) {
        if (purchases == null) return;
        for (Purchase purchase : purchases) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                notifyState("Coin purchase pending payment", false);
                continue;
            }
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;

            String productId = null;
            for (String product : purchase.getProducts()) {
                if (SUPPORTED_PRODUCTS.contains(product)) {
                    productId = product;
                    break;
                }
            }
            if (productId == null) continue;

            String token = purchase.getPurchaseToken();
            synchronized (submittingTokens) {
                if (submittingTokens.contains(token)) continue;
                submittingTokens.add(token);
            }
            String finalProductId = productId;
            cloud.submitGooglePurchase(finalProductId, token, (success, message) -> {
                synchronized (submittingTokens) {
                    submittingTokens.remove(token);
                }
                notifyState(message, success);
            });
        }
    }

    private void notifyState(String message, boolean profileChanged) {
        activity.runOnUiThread(() -> {
            if (listener != null) listener.onBillingState(message, profileChanged);
        });
    }

    public void close() {
        closed = true;
        ready = false;
        submittingTokens.clear();
        billingClient.endConnection();
    }
}
