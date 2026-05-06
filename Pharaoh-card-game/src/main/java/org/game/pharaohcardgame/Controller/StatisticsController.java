package org.game.pharaohcardgame.Controller;

import lombok.RequiredArgsConstructor;

import org.game.pharaohcardgame.Model.DTO.Response.RoomStatisticsResponse;

import org.game.pharaohcardgame.Model.DTO.Response.UserStatisticsResponse;
import org.game.pharaohcardgame.Service.Implementation.AuthenticationService;
import org.game.pharaohcardgame.Service.Implementation.StatisticsService;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;
    private final AuthenticationService authenticationService;

    /**
     * Lekéri az aktuális felhasználó általános statisztikáit
     */
    @GetMapping("/user/me")
    public ResponseEntity<UserStatisticsResponse> getMyStatistics() {
        var user = authenticationService.getAuthenticatedUser();
        UserStatisticsResponse stats = statisticsService.getUserStatistics(user.getId());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/user/global-best")
    public ResponseEntity<CompletableFuture<UserStatisticsResponse>> getGlobalBest() {
        var user = authenticationService.getAuthenticatedUser();
        CompletableFuture<UserStatisticsResponse> stats = statisticsService.getGlobalBest();
        return ResponseEntity.ok(stats);
    }


}