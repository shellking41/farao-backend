package org.game.pharaohcardgame.Utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.game.pharaohcardgame.Enum.CardRank;
import org.game.pharaohcardgame.Enum.CardSuit;
import org.game.pharaohcardgame.Exception.LockAcquisitionException;
import org.game.pharaohcardgame.Exception.LockInterruptedException;
import org.game.pharaohcardgame.Model.DTO.Request.CardRequest;
import org.game.pharaohcardgame.Model.DTO.RequestMapper;
import org.game.pharaohcardgame.Model.DTO.Response.CardInHandResponse;
import org.game.pharaohcardgame.Model.DTO.Response.FinalPositionEntry;
import org.game.pharaohcardgame.Model.DTO.Response.PlayCardResponse;
import org.game.pharaohcardgame.Model.DTO.Response.PlayerHandResponse;
import org.game.pharaohcardgame.Model.DTO.ResponseMapper;
import org.game.pharaohcardgame.Model.GameSession;
import org.game.pharaohcardgame.Model.Player;
import org.game.pharaohcardgame.Model.RedisModel.Card;
import org.game.pharaohcardgame.Model.RedisModel.GameState;
import org.game.pharaohcardgame.Model.Results.NextTurnResult;
import org.game.pharaohcardgame.Service.Implementation.CacheService;

import org.game.pharaohcardgame.Service.Implementation.StatisticsService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
@Component
public class GameSessionUtils {
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private static final String GAME_STATE_KEY = "gameState_";
    private static final int GAME_STATE_TTL = 24;

    private final ObjectMapper objectMapper;
    private final CacheService cacheService;
    private final CacheManager cacheManager;

    private final ResponseMapper responseMapper;
    private final RequestMapper requestMapper;
    private final StatisticsService statisticsService;


    public GameState getGameState(Long gameSessionId) {
        String key = GAME_STATE_KEY + gameSessionId;
        Cache cache = cacheManager.getCache("gameState");
        GameState gameState = cacheService.getCachedData(cache, key, "gameState", GameState.class);

        if (gameState == null) {
            return null;
        }


        try {
            return gameState;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize game state", e);
        }
    }

    @Transactional
    public void recordGameStatistics(GameState gameState, GameSession gameSession, boolean isGamemasterLeft) {
        @SuppressWarnings("unchecked")
        Map<Long, FinalPositionEntry> finalPositions = getSpecificGameDataTypeMap("finalPositions", gameState);

        if (finalPositions.isEmpty()) {
            log.warn("No final positions found for game session {}", gameSession.getGameSessionId());
            return;
        }

        // Csak USER játékosokat dolgozzuk fel (NEM botokat)
        for (Player player : gameSession.getPlayers()) {
            if (player.getIsBot() || player.getUser() == null) {
                continue; // Skip bots
            }
            FinalPositionEntry entry = finalPositions.get(player.getPlayerId());

            if (entry != null) {
                Integer position = entry.getPosition();
                log.info("Player {} finished at position {}", player.getUser().getName(), position)
                ;
                if (position == null) {
                    log.warn("No position found for player {}", player.getPlayerId());
                    continue;
                }

                boolean isWinner = position == 1;

                // Rögzítjük a játék eredményét
                statisticsService.recordGameResult(
                        player.getUser(),
                        gameSession.getRoom(),
                        gameSession,
                        isWinner,
                        position
                );
            }
            ;

        }

        //Ha NEM gamemaster kilépés miatt ért véget, AKKOR számoljuk be a statisztikákba
        if (!isGamemasterLeft) {
            statisticsService.updateStatisticsAfterGameEnd(gameSession.getGameSessionId());
            log.info("Game statistics updated for game session {}", gameSession.getGameSessionId());
        } else {
            // Gamemaster kilépés esetén eldobjuk a nem számolt statisztikákat
            statisticsService.discardUncountedStatistics(gameSession.getGameSessionId());
            log.info("Game statistics discarded for game session {} (gamemaster left)",
                    gameSession.getGameSessionId());
        }
    }

    public void deleteGameState(Long gameSessionId) {
        String key = GAME_STATE_KEY + gameSessionId;
        Cache cache = cacheManager.getCache("gameState");
        if (cache != null) {
            cache.evict(key);
        }

        log.info("Deleted game state for session: {}", gameSessionId);
    }

    @SuppressWarnings("unchecked")
    public <T> Set<T> getSpecificGameDataTypeSet(String key, GameState gameState) {
        Object value = gameState.getGameData().get(key);

        if (value == null) {
            Set<T> newSet = new HashSet<>();
            gameState.getGameData().put(key, newSet); // vissza a gameState-be
            return newSet;
        }

        if (!(value instanceof Set<?>)) {
            throw new IllegalArgumentException("Value for key '" + key + "' is not a Set!");
        }

        return (Set<T>) value;
    }

    @SuppressWarnings("unchecked")
    public <K, V> Map<K, V> getSpecificGameDataTypeMap(String key, GameState gameState) {
        Object value = gameState.getGameData().get(key);

        if (value == null) {
            Map<K, V> newMap = new HashMap<>();
            gameState.getGameData().put(key, newMap);
            return newMap;
        }
        ;

        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Value for key '" + key + "' is not a Map!");
        }

        return (Map<K, V>) value;
    }


    @SuppressWarnings("unchecked")
    public <T> T getSpecificGameData(String key, GameState gameState, T defaultValue) {
        Object value = gameState.getGameData().get(key);

        if (value == null) {
            if (defaultValue != null) {
                gameState.getGameData().put(key, defaultValue); // vissza a gameState-be
            }
            return defaultValue;
        }

        try {
            return (T) value;
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Invalid type for key: " + key, e);
        }
    }

    //it ez kulon vissza adja a sajat kartyait  ausernek es adatok nelkul a masik userek kartya számát.
    public PlayerHandResponse getPlayerHand(Long gameSessionId, Long playerId) {
        GameState gameState = getGameState(gameSessionId);
        if (gameState == null) {
            throw new EntityNotFoundException("Game state not found for session: " + gameSessionId);
        }

        List<Card> ownCards = gameState.getPlayerHands().get(playerId);
        if (ownCards == null) {
            throw new EntityNotFoundException("Player not found in game");
        }
        // Saját kártyák konvertálása
        List<CardInHandResponse> ownCardInHandRespons = responseMapper.toCardInHandResponseList(ownCards);


        // Más játékosok kártyaszámai
        Map<Long, Integer> otherPlayersCardCount = gameState.getPlayerHands().entrySet()
                .stream()
                .filter(entry -> !entry.getKey().equals(playerId))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().size()
                ));

        return PlayerHandResponse.builder()
                .playerId(playerId)
                .ownCards(ownCardInHandRespons)
                .otherPlayersCardCount(otherPlayersCardCount)
                .build();
    }

    public List<Card> createShuffledDeck() {

        List<Card> deck = new ArrayList<>();
        for (CardSuit suit : CardSuit.values()) {
            for (CardRank rank : CardRank.values()) {
                Card card = Card.builder()
                        .cardId(UUID.randomUUID().toString())
                        .suit(suit)
                        .rank(rank)
                        .build();
                deck.add(card);
            }
        }

        Collections.shuffle(deck);

        return deck;
    }


    //todo: kell ide a version check system
    public GameState updateGameState(Long gameSessionId, Function<GameState, GameState> updater) {
        String cacheKey = GAME_STATE_KEY + gameSessionId;
        Cache cache = cacheManager.getCache("gameState");
        ReentrantLock lock = locks.computeIfAbsent(cacheKey, k -> new ReentrantLock());
        boolean locked = false;
        try {
            locked = lock.tryLock(30, TimeUnit.SECONDS);
            if (!locked) {
                throw new LockAcquisitionException("Failed to acquire lock");
            }
            GameState currentGameState = getGameState(gameSessionId);
            //frissitjük a gameState-t
            GameState updatedGameState = updater.apply(currentGameState);
            if (updatedGameState == null) {
                throw new EntityNotFoundException("Game state not found for session: " + gameSessionId);
            }
            // elmentjük cachebe
            cacheService.saveInCache(cache, cacheKey, updatedGameState, "gameState");


            return updatedGameState;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockInterruptedException("Thread interrupted while acquiring lock", e);
        } finally {
            if (locked) {
                lock.unlock();
            }
            if (!lock.isLocked()) {
                locks.remove(cacheKey, lock);
            }
        }
    }

    public NextTurnResult calculateNextTurn(Player currentPlayer, GameSession gameSession, GameState gameState, Integer skipPlayerCount) {

        if (skipPlayerCount == null) {
            skipPlayerCount = 0;
        }

        List<Player> players = gameSession.getPlayers();
        List<Integer> seatIndexes = players.stream().map(Player::getSeat).toList();

        Set<Long> finishedPlayers = getSpecificGameDataTypeSet("finishedPlayers", gameState);
        Set<Long> lostPlayers = getSpecificGameDataTypeSet("lostPlayers", gameState);
        Set<Long> skippedPlayers = getSpecificGameDataTypeSet("skippedPlayers", gameState);

        int currentSeatPosition = seatIndexes.indexOf(currentPlayer.getSeat());
        if (currentSeatPosition == -1) {
            throw new IllegalStateException("Current Player's seat is not found");
        }

        int nextSeatPosition = currentSeatPosition;
        Player nextPlayer = null;
        int nextSeatIndex = -1;

        //todo: TALÁN MINDEN MUKODIK
        // Ha skipPlayerCount > 0, itt léptetjük át a játékosokat
        //todo: talán nincs gond ha saját magát és skippeli a játékos
        //todo: csak azzal van a baj ha egy player kiléppett akkor megbolondul
        for (int i = 0; i < skipPlayerCount; i++) {
            nextSeatPosition = (nextSeatPosition + 1) % seatIndexes.size();
            final int seatToCheck = seatIndexes.get(nextSeatPosition);

            Player skipped = players.stream()
                    .filter(p -> p.getSeat().equals(seatToCheck))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Player is not found on the current seat"));

            // Csak akkor adjuk hozzá, ha nem fejezte be és nem vesztett
            if (!finishedPlayers.contains(skipped.getPlayerId()) && !lostPlayers.contains(skipped.getPlayerId())) {
                //todo: itt valamiert nem skippel senkit amikor pl. a 2. player lerakja a asz és a 3. player mar kiszált akkor a 1. player kezd mert figyeli azt hogy a 3. a finished player és nem jut ide ebbe a ifbe bele
                skippedPlayers.add(skipped.getPlayerId()); // <-- ide kerül a skipelt player
            } else {
                //ha nem tudja beallitani a playert akkor megprobalja beallitani a akovetkezo playert
                skipPlayerCount++;
            }
        }

        //todo: van baj azzal hogy ha mondjuk 3 player van és 4 asz kartyat teszunk le. ezt meg kell oldani
        //todo: volt baj azzal is amikor a elso player kiszált majd a 3. player lerakott egy ászt akkor nem következett ő ujra hanem a 2. player kovetkezett.
        // Most találjuk meg a tényleges nextPlayer-t
        do {
            nextSeatPosition = (nextSeatPosition + 1) % seatIndexes.size();
            final int seatToCheck = seatIndexes.get(nextSeatPosition);

            nextPlayer = players.stream()
                    .filter(p -> p.getSeat().equals(seatToCheck))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Player is not found on the current seat"));

            nextSeatIndex = seatToCheck;
            //ezt kiszedtem mert volt baj azzal hogy ha befejezte a player a kort kozben az ász kartya végett pon to kovewtkezett volna akkor ez megoldotta
        } while ((finishedPlayers.contains(nextPlayer.getPlayerId()) || lostPlayers.contains(nextPlayer.getPlayerId())) /*&& nextSeatPosition != currentSeatPosition*/);

        NextTurnResult nextTurnResult = new NextTurnResult(nextPlayer, nextSeatIndex);

        if (finishedPlayers.contains(nextTurnResult.nextPlayer().getPlayerId())) {
            throw new IllegalStateException("All players have finished - this should not happen");
        }

        return nextTurnResult;
    }

    public List<List<Card>> calculateValidPlays(GameState gameState, Player currentPlayer) {
        // Játékos keze
        List<Card> hand = gameState.getPlayerHands().get(currentPlayer.getPlayerId());
        if (hand == null || hand.isEmpty()) return Collections.emptyList();

        // Utolsó lejátszott kártya
        List<Card> playedCards = gameState.getPlayedCards();
        if (playedCards == null || playedCards.isEmpty()) return Collections.emptyList();
        Card lastPlayedCard = playedCards.getLast();

        // Színváltás
        CardSuit suitChangedTo = getSpecificGameData("suitChangedTo", gameState, null);

        // Letehető starter kártyák
        List<Card> playableFirstCards = hand.stream()
                .filter(c -> isCardPlayable(c, lastPlayedCard, suitChangedTo, gameState, currentPlayer))
                .toList();

        List<List<Card>> validPlays = new ArrayList<>();

        // Egykártyás lépések
        for (Card single : playableFirstCards) {
            validPlays.add(Collections.singletonList(single));
        }


        Map<CardRank, List<Card>> groupsByRank = hand.stream()
                .collect(Collectors.groupingBy(Card::getRank, LinkedHashMap::new, Collectors.toList()));

        //  kombinációk
        for (List<Card> group : groupsByRank.values()) {
            if (group.size() < 2) continue;

            int n = group.size();

            // Minden 2..n méretű kombináció
            for (int size = 2; size <= n; size++) {
                List<List<Card>> combos = generateCombinations(group, size);

                for (List<Card> combo : combos) {

                    // Minden permutációt hozzáadunk
                    List<List<Card>> perms = new ArrayList<>();
                    permute(combo, 0, perms);
                    for (List<Card> perm : perms) {
                        // Csak akkor vegyük fel, ha az első kártya playable starter
                        if (playableFirstCards.stream().anyMatch(c -> c.getCardId().equals(perm.get(0).getCardId()))) {
                            validPlays.add(new ArrayList<>(perm));
                        }
                    }
                }
            }
        }

        return validPlays;
    }

    // Ellenőrzi, hogy a kártya letehető-e
    private boolean isCardPlayable(Card c, Card lastPlayedCard, CardSuit suitChangedTo, GameState gameState, Player player) {
        // Hetes húzós szabály
        if (lastPlayedCard.getRank() == CardRank.VII &&
                getSpecificGameDataTypeMap("drawStack", gameState).containsKey(player.getPlayerId())) {
            return c.getRank() == CardRank.VII || (c.getRank() == CardRank.JACK && c.getSuit() == CardSuit.LEAVES);
        }

        if (c.getRank() == CardRank.OVER) return true;

        if (c.getRank() == lastPlayedCard.getRank()) return true;

        if (lastPlayedCard.getRank() == CardRank.JACK && lastPlayedCard.getSuit() == CardSuit.LEAVES) return true;

        if (suitChangedTo == null && c.getSuit() == lastPlayedCard.getSuit()) return true;

        return suitChangedTo != null && c.getSuit() == suitChangedTo;
    }

    //  permutációkat csinal a kombinációkból azaz minden sorrendet lehetőségul vesz
    private void permute(List<Card> list, int start, List<List<Card>> result) {
        if (start >= list.size() - 1) {
            result.add(new ArrayList<>(list));
            return;
        }

        for (int i = start; i < list.size(); i++) {
            Collections.swap(list, start, i);
            permute(list, start + 1, result);
            Collections.swap(list, start, i);
        }
    }

    // Generál minden  kombinációt a listából
    private List<List<Card>> generateCombinations(List<Card> cards, int size) {
        List<List<Card>> result = new ArrayList<>();
        combine(cards, 0, size, new ArrayList<>(), result);
        return result;
    }

    //  részhalmaz generálás
    private void combine(List<Card> cards, int start, int size, List<Card> path, List<List<Card>> result) {
        if (path.size() == size) {
            result.add(new ArrayList<>(path));
            return;
        }
        for (int i = start; i < cards.size(); i++) {
            path.add(cards.get(i));
            combine(cards, i + 1, size, path, result);
            path.removeLast();
        }
    }

}
