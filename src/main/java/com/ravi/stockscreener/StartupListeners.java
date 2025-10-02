package com.ravi.stockscreener;

import com.ravi.stockscreener.scanservice.MarketMorningScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class StartupListeners {

    private final MarketMorningScheduler morningScheduler;

    // fired when the application is ready to service requests (after runners)
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        System.out.println("ApplicationReadyEvent - app is ready");
        morningScheduler.startApp();
    }

    // ContextRefreshedEvent happens earlier, when the ApplicationContext is refreshed
    @EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        System.out.println("Context refreshed");
    }
}

