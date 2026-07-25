package com.quorum.loadbalancer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LoadBalancerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoadBalancerApplication.class, args);
        
        System.out.println("\n  ╔══════════════════════════════════════════╗");
        System.out.println("  ║  AI Load Balancer Service Iniciado       ║");
        System.out.println("  ║  Puerto: 8000                            ║");
        System.out.println("  ╚══════════════════════════════════════════╝\n");
    }
}
