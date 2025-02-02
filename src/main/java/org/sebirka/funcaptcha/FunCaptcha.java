package org.sebirka.funcaptcha;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class FunCaptcha extends JavaPlugin implements Listener {

    // Префикс для всех сообщений плагина
    private static final String PREFIX = ChatColor.GOLD + "[FunCaptcha] " + ChatColor.RESET;

    // Храним активные капчи для игроков: UUID -> CaptchaChallenge
    private final Map<UUID, CaptchaChallenge> activeChallenges = new HashMap<>();
    private final Random random = new Random();

    /*
     * Таблица для преобразования латинских символов в кириллические аналоги.
     * Например, латинская "e" (U+0065) и кириллическая "е" (U+0435) выглядят идентично.
     */
    private static final Map<Character, Character> latinToCyrillicMap = new HashMap<>();
    static {
        // Заглавные буквы
        latinToCyrillicMap.put('A', 'А');
        latinToCyrillicMap.put('B', 'В');
        latinToCyrillicMap.put('C', 'С');
        latinToCyrillicMap.put('E', 'Е');
        latinToCyrillicMap.put('H', 'Н');
        latinToCyrillicMap.put('K', 'К');
        latinToCyrillicMap.put('M', 'М');
        latinToCyrillicMap.put('O', 'О');
        latinToCyrillicMap.put('P', 'Р');
        latinToCyrillicMap.put('T', 'Т');
        latinToCyrillicMap.put('X', 'Х');
        // Строчные буквы
        latinToCyrillicMap.put('a', 'а');
        latinToCyrillicMap.put('c', 'с');
        latinToCyrillicMap.put('e', 'е');
        latinToCyrillicMap.put('o', 'о');
        latinToCyrillicMap.put('p', 'р');
        latinToCyrillicMap.put('x', 'х');
    }

    /*
     * Класс, описывающий капчу для игрока.
     * originalText — правильное слово(а) (до преобразования);
     * displayedText — текст, отправленный игроку (с заменёнными символами);
     * attempts — число неправильных попыток;
     * timeoutTask — задача, кикающая игрока, если он не прошёл капчу вовремя.
     */
    private class CaptchaChallenge {
        private final String originalText;
        private final String displayedText;
        private int attempts;
        private final int maxAttempts = 3;
        private final BukkitRunnable timeoutTask;

        public CaptchaChallenge(String originalText, String displayedText, BukkitRunnable timeoutTask) {
            this.originalText = originalText;
            this.displayedText = displayedText;
            this.timeoutTask = timeoutTask;
            this.attempts = 0;
        }
    }

    @Override
    public void onEnable() {
        getLogger().info(PREFIX + "Плагин включён.");
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig(); // Конфигурация для запоминания проверенных игроков
    }

    @Override
    public void onDisable() {
        for (CaptchaChallenge challenge : activeChallenges.values()) {
            challenge.timeoutTask.cancel();
        }
        activeChallenges.clear();
        getLogger().info(PREFIX + "Плагин выключён.");
    }

    // При входе игрока проверяем, пройдена ли капча; если нет — генерируем её
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isVerified(player)) {
            generateCaptchaForPlayer(player);
        }
    }

    // Блокируем перемещение, если капча не пройдена
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (activeChallenges.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            clearAndResendCaptcha(player);
        }
    }

    // Блокируем выполнение команд, если капча не пройдена
    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (activeChallenges.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            clearAndResendCaptcha(player);
        }
    }

    // Обработка чата: сравниваем нормализованный ответ игрока с нормализованным оригинальным текстом капчи
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!activeChallenges.containsKey(uuid)) return;

        event.setCancelled(true); // не показываем сообщение в общий чат
        CaptchaChallenge challenge = activeChallenges.get(uuid);
        String input = event.getMessage().trim();

        if (normalize(input).equals(normalize(challenge.originalText))) {
            markVerified(player);
            player.sendMessage(PREFIX + ChatColor.GREEN + "Капча пройдена! Добро пожаловать на сервер.");
            challenge.timeoutTask.cancel();
            activeChallenges.remove(uuid);
        } else {
            challenge.attempts++;
            if (challenge.attempts >= challenge.maxAttempts) {
                player.kickPlayer(PREFIX + ChatColor.RED + "Вы не прошли капчу. Попробуйте зайти позже.");
                challenge.timeoutTask.cancel();
                activeChallenges.remove(uuid);
            } else {
                clearAndResendCaptcha(player);
            }
        }
    }

    // Генерация капчи для игрока
    private void generateCaptchaForPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        // Сначала генерируем оригинальный текст капчи (2–4 слова)
        String originalText = generateMixedCaptchaText();
        // Преобразуем его для отображения: заменяем некоторые символы на их альтернативы
        String displayedText = swapLettersRandomly(originalText);

        BukkitRunnable timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (activeChallenges.containsKey(uuid)) {
                    player.kickPlayer(PREFIX + ChatColor.RED + "Вы не прошли капчу вовремя.");
                    activeChallenges.remove(uuid);
                }
            }
        };
        timeoutTask.runTaskLater(this, 30 * 20L); // 30 секунд = 600 тиков

        activeChallenges.put(uuid, new CaptchaChallenge(originalText, displayedText, timeoutTask));
        clearAndResendCaptcha(player);
    }

    // Очистка чата и повторная отправка инструкции капчи
    private void clearAndResendCaptcha(Player player) {
        for (int i = 0; i < 50; i++) {
            player.sendMessage("");
        }
        UUID uuid = player.getUniqueId();
        if (!activeChallenges.containsKey(uuid)) return;
        CaptchaChallenge challenge = activeChallenges.get(uuid);
        String instruction = PREFIX + ChatColor.AQUA +
                "Чтобы подтвердить, что вы не бот, введите ниже точно этот текст:" +
                "\n" + ChatColor.YELLOW + challenge.displayedText;
        player.sendMessage(instruction);
    }

    // Генерация капчи: выбираем 2–4 случайных слова из массивов (русских и английских)
    private String generateMixedCaptchaText() {
        String[] russianWords = {"безопасность", "сервер", "игрок", "капча", "информация", "контроль"};
        String[] englishWords = {"security", "server", "player", "captcha", "info", "control"};
        int wordCount = 2 + random.nextInt(3); // от 2 до 4 слов

        List<String> words = new ArrayList<>();
        for (int i = 0; i < wordCount; i++) {
            boolean useRussian = random.nextBoolean();
            String word = useRussian ? russianWords[random.nextInt(russianWords.length)]
                    : englishWords[random.nextInt(englishWords.length)];
            words.add(word);
        }
        Collections.shuffle(words);
        return String.join(" ", words);
    }

    /*
     * swapLettersRandomly(String input)
     * Проходит по каждому символу исходного текста и с вероятностью 50% заменяет его на альтернативу.
     * Если символ встречается в таблице latinToCyrillicMap (например, латинская "h"),
     * он заменяется на соответствующий кириллический символ (в данном случае, "н").
     * Если символ уже кириллический и есть обратное соответствие (ищется перебором),
     * он заменяется на латинский аналог.
     */
    private String swapLettersRandomly(String input) {
        StringBuilder sb = new StringBuilder();
        for (char ch : input.toCharArray()) {
            if (random.nextDouble() < 0.5) { // 50% вероятности
                if (latinToCyrillicMap.containsKey(ch)) {
                    ch = latinToCyrillicMap.get(ch);
                } else {
                    // Если символ не латинский, попробуем заменить кириллический на латинский (обратное преобразование)
                    for (Map.Entry<Character, Character> entry : latinToCyrillicMap.entrySet()) {
                        if (entry.getValue().equals(ch)) {
                            ch = entry.getKey();
                            break;
                        }
                    }
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /*
     * normalize(String input)
     * Приводит строку к канонической форме: для каждого символа, если он является латинским и имеет кириллический аналог,
     * заменяет его на кириллический. Таким образом, и ответ, и оригинальный текст капчи будут приведены к единому виду.
     */
    private String normalize(String input) {
        StringBuilder sb = new StringBuilder();
        for (char ch : input.toCharArray()) {
            if (latinToCyrillicMap.containsKey(ch)) {
                sb.append(latinToCyrillicMap.get(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // Проверяем, прошёл ли игрок капчу (смотрим в конфигурации)
    private boolean isVerified(Player player) {
        return getConfig().getBoolean("verifiedPlayers." + player.getUniqueId().toString(), false);
    }

    // Отмечаем игрока как проверенного и сохраняем в конфигурации
    private void markVerified(Player player) {
        getConfig().set("verifiedPlayers." + player.getUniqueId().toString(), true);
        saveConfig();
    }
}
