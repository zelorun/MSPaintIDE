package com.uddernetworks.mspaint.main;

import com.uddernetworks.mspaint.settings.Setting;
import com.uddernetworks.mspaint.settings.SettingsManager;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ThemeManager {

    private static Logger LOGGER = LoggerFactory.getLogger(ThemeManager.class);

    private Map<String, String> themes = new HashMap<>();
    private List<Scene> scenes = new ArrayList<>();
    private String activeName;
    private String activePath;

    public void loadTheme(String name, String path) {
        LOGGER.info("Loading theme \"" + name + "\"");
        themes.put(name, path);
    }

    public void init() {
        SettingsManager.getInstance().<String>onChangeSetting(Setting.ACTIVE_THEME_NAME, theme -> selectThemeName(this.activeName = theme), true);
    }

    public Map<String, String> getAllThemes() {
        return this.themes;
    }

    public void addStage(Stage stage) {
        Scene scene = stage.getScene();
        stage.setOnCloseRequest(event -> removeScene(scene));
        if (!this.scenes.contains(scene)) this.scenes.add(scene);
        selectThemeName(this.activeName);
    }

    public void removeScene(Scene scene) {
        this.scenes.remove(scene);
    }

    public void selectThemeName(String name) {
        if (!this.themes.containsKey(name)) return;

        var themePath = this.themes.get(name);
        if (!themePath.startsWith("themes")) themePath = new File(themePath).toURI().toString();
        var removingTheme = this.activePath;
        this.activePath = themePath;

        this.scenes.stream().map(Scene::getStylesheets).forEach(sheets -> {
            if (removingTheme != null) sheets.remove(removingTheme);
            sheets.add(activePath);
            saveSettings();
        });

        this.activeName = name;
    }

    public void removeThemeName(String name) {
        if (!this.themes.containsKey(name)) return;
        this.themes.remove(name);
        this.scenes.stream().map(Scene::getStylesheets).forEach(sheets -> sheets.remove(this.themes.get(name)));
    }

    public ThemeChanger onDarkThemeChange(Parent root, Map<String, String> classToDark) {
        var initialMap = new HashMap<>(Map.of(
                ".gridpane-theme", "gridpane-theme-dark",
                ".theme-text", "dark-text"));
        initialMap.putAll(classToDark);
        return new ThemeChanger(root, initialMap);
    }

    public String getActiveTheme() {
        return this.activeName;
    }

    public void modifyThemeName(String name, String newName) {
        var value = this.themes.remove(name);
        this.themes.put(newName, value);
        saveSettings();
    }

    public void modifyThemePath(String name, String path) {
        this.themes.replace(name, path);
        saveSettings();
    }

    public void saveSettings() {
        SettingsManager.getInstance().setSetting(Setting.THEMES, this.themes);
    }

    public class ThemeChanger {
        private Parent root;
        private Map<String, String> classToDark;
        private Consumer<Boolean> onChange = newValue ->
                classToDark.forEach((key, value) -> root.lookupAll(key)
                        .stream()
                        .map(Node::getStyleClass)
                        .forEach(styles -> {
                            if (newValue) {
                                styles.add(value);
                            } else {
                                styles.remove(value);
                            }
                        }));

        public ThemeChanger(Parent root, Map<String, String> classToDark) {
            this.root = root;
            this.classToDark = classToDark;
            init();
        }

        private void init() {
            SettingsManager.getInstance().onChangeSetting(Setting.DARK_THEME, onChange, true);
        }

        public void update(long delay, TimeUnit unit) {
            CompletableFuture.delayedExecutor(delay, unit).execute(() -> Platform.runLater(this::update));
        }

        public void update() {
            onChange.accept(SettingsManager.getInstance().getSetting(Setting.DARK_THEME));
        }
    }
}
