package com.uddernetworks.mspaint.main;

import com.uddernetworks.mspaint.code.GeneralRunningCodeManager;
import com.uddernetworks.mspaint.code.ImageClass;
import com.uddernetworks.mspaint.code.RunningCodeManager;
import com.uddernetworks.mspaint.code.highlighter.AngrySquiggleHighlighter;
import com.uddernetworks.mspaint.code.languages.Language;
import com.uddernetworks.mspaint.code.languages.LanguageError;
import com.uddernetworks.mspaint.code.languages.LanguageManager;
import com.uddernetworks.mspaint.code.languages.brainfuck.BrainfuckLanguage;
import com.uddernetworks.mspaint.code.languages.java.JavaLanguage;
import com.uddernetworks.mspaint.code.languages.python.PythonLanguage;
import com.uddernetworks.mspaint.imagestreams.ImageOutputStream;
import com.uddernetworks.mspaint.ocr.OCRManager;
import com.uddernetworks.mspaint.painthook.InjectionManager;
import com.uddernetworks.mspaint.project.PPFProject;
import com.uddernetworks.mspaint.project.ProjectManager;
import com.uddernetworks.mspaint.settings.Setting;
import com.uddernetworks.mspaint.settings.SettingsManager;
import com.uddernetworks.mspaint.splash.Splash;
import com.uddernetworks.mspaint.splash.SplashMessage;
import com.uddernetworks.mspaint.texteditor.CenterPopulator;
import org.apache.batik.transcoder.TranscoderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class Main {

    private static Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static File currentJar;

    private MainGUI mainGUI;

    private List<ImageClass> imageClasses = new ArrayList<>();

    private LanguageManager languageManager = new LanguageManager();
    private Language currentLanguage;
    private RunningCodeManager runningCodeManager;
    private OCRManager ocrManager;

    private CenterPopulator centerPopulator;

    private Method addURL;
    private List<String> added = new ArrayList<>();
    private Consumer<PPFProject> projectConsumer;

    public void start(MainGUI mainGUI) throws IOException {
        headlessStart();
        this.mainGUI = mainGUI;

        addPath(MainGUI.APP_DATA.getAbsolutePath());
        new File(MainGUI.APP_DATA, "fonts").mkdirs();
        new File(MainGUI.APP_DATA, "themes").mkdirs();

        this.mainGUI.setDarkTheme(SettingsManager.getInstance().getSetting(Setting.DARK_THEME));
        this.mainGUI.updateTheme();

        Splash.setStatus(SplashMessage.ADDING_LANGUAGES);

        languageManager.addLanguage(new JavaLanguage());
        languageManager.addLanguage(new BrainfuckLanguage());
        languageManager.addLanguage(new PythonLanguage());

        languageManager.initializeLanguages();
        mainGUI.addLanguages(languageManager.getEnabledLanguages());

        this.runningCodeManager = new GeneralRunningCodeManager(this);

        new InjectionManager(mainGUI, this).createHooks();
    }

    public void headlessStart() throws IOException {
        LOGGER.info("Loading settings");
        Splash.setStatus(SplashMessage.SETTINGS);
        var optionsFile = new File(MainGUI.APP_DATA, "options.ini");
        var initializeSettings = !optionsFile.exists();
        var settingsManager = SettingsManager.getInstance();
        settingsManager.initialize(optionsFile);
        this.centerPopulator = new CenterPopulator(this);

        Splash.setStatus(SplashMessage.DATABASE);
        this.ocrManager = new OCRManager(this);

        if (initializeSettings) {
            settingsManager.setSetting(Setting.THEMES, Map.of(
                    "Default", "themes/default.css",
                    "Extra Dark", "themes/extra-dark.css"
            ));

            if (!MainGUI.HEADLESS && ProjectManager.getPPFProject() != null) {
                settingsManager.setSetting(Setting.TRAIN_IMAGE, ProjectManager.getPPFProject().getFile().getParentFile().getAbsolutePath() + "\\train.png");
            }
        }

        if (MainGUI.HEADLESS) {
            settingsManager.<String>onChangeSetting(Setting.HEADLESS_FONT, font -> {
                this.ocrManager.setActiveFont(font, settingsManager.getSetting(Setting.HEADLESS_FONT_CONFIG));
            }, true);
        } else {
            ProjectManager.switchProjectConsumer(project -> {
                System.out.println("Active font: " + project.getActiveFont() + " (" + project.getActiveFont().trim().equals("") + ")");
                System.out.println("Active font: " + project.getActiveFontConfig() + " (" + project.getActiveFontConfig().trim().equals("") + ")");
                if (project.getActiveFont() == null) project.setActiveFont(settingsManager.getSetting(Setting.HEADLESS_FONT));
                project.onFontUpdate((name, path) -> this.ocrManager.setActiveFont(name, path), true);
            });
        }
    }

    public void setCurrentLanguage(Language language) {
        this.currentLanguage = language;
    }

    public Language getCurrentLanguage() {
        return this.currentLanguage;
    }

    private boolean optionsNotFilled() {
        var ppfProject = ProjectManager.getPPFProject();
        var lang = getCurrentLanguage();
        return ppfProject.getInputLocation() == null || !lang.getLanguageSettings().requiredFilled() || (getCurrentLanguage().getOutputFileExtension() != null && ppfProject.getCompilerOutput() == null);
    }

    public int indexAll() {
        if (optionsNotFilled()) {
            LOGGER.error("Please select files for all options");
            mainGUI.setHaveError();
            return -1;
        }

        LOGGER.info("Scanning all images...");
        long start = System.currentTimeMillis();

        mainGUI.setStatusText(null);

        File inputImage = ProjectManager.getPPFProject().getInputLocation();

        if (inputImage.isDirectory()) {
            LOGGER.info("Found directory: " + inputImage.getAbsolutePath());
            for (File imageFile : getFilesFromDirectory(inputImage, this.currentLanguage.getFileExtensions(), "png")) {
                LOGGER.info("Adding non directory: " + imageFile.getAbsolutePath());
                imageClasses.add(new ImageClass(imageFile, mainGUI));
            }
        } else {
            LOGGER.info("Adding non directory: " + inputImage.getAbsolutePath());
            imageClasses.add(new ImageClass(inputImage, mainGUI));
        }

        mainGUI.setStatusText(null);

        LOGGER.info("Finished scanning all images in " + (System.currentTimeMillis() - start) + "ms");
        return 1;
    }

    public void highlightAll() throws IOException {
        if (optionsNotFilled()) {
            LOGGER.error("Please select files for all options");
            mainGUI.setHaveError();
            return;
        }

        File highlightedFile = ProjectManager.getPPFProject().getHighlightLocation();

        if (highlightedFile != null && !highlightedFile.isDirectory()) highlightedFile.mkdirs();

        if (highlightedFile == null || !highlightedFile.isDirectory()) {
            LOGGER.error("No highlighted file directory found!");
            mainGUI.setHaveError();
            return;
        }

        LOGGER.info("Scanning all images...");
        mainGUI.setStatusText("Highlighting...");
        mainGUI.setIndeterminate(true);
        long start = System.currentTimeMillis();

        for (ImageClass imageClass : imageClasses) {
            imageClass.highlight(highlightedFile);
        }

        mainGUI.setIndeterminate(false);
        mainGUI.setStatusText(null);

        LOGGER.info("Finished highlighting all images in " + (System.currentTimeMillis() - start) + "ms");
    }


    public void compile(boolean execute) throws IOException {
        long start = System.currentTimeMillis();

        if (getCurrentLanguage().isInterpreted()) {
            LOGGER.info("Interpreting...");
            mainGUI.setStatusText("Interpreting...");
        } else {
            LOGGER.info("Compiling...");
            mainGUI.setStatusText("Compiling...");
        }

        File libraryFile = ProjectManager.getPPFProject().getLibraryLocation();

        mainGUI.setIndeterminate(true);

        List<File> libFiles = new ArrayList<>();
        if (libraryFile != null) {
            if (libraryFile.isFile()) {
                if (libraryFile.getName().endsWith(".jar")) {
                    libFiles.add(libraryFile);
                }
            } else {
                libFiles.addAll(getFilesFromDirectory(libraryFile, "jar"));
            }
        }

        ImageOutputStream imageOutputStream = new ImageOutputStream(this, ProjectManager.getPPFProject().getAppOutput(), 500);
        ImageOutputStream compilerOutputStream = new ImageOutputStream(this, ProjectManager.getPPFProject().getCompilerOutput(), 500);
        Map<ImageClass, List<LanguageError>> errors = null;

        try {
            errors = getCurrentLanguage().compileAndExecute(imageClasses, ProjectManager.getPPFProject().getJarFile(), ProjectManager.getPPFProject().getOtherLocation(), ProjectManager.getPPFProject().getClassLocation(), mainGUI, imageOutputStream, compilerOutputStream, libFiles, execute);

            LOGGER.info("Highlighting Angry Squiggles...");
            mainGUI.setStatusText("Highlighting Angry Squiggles...");

            for (ImageClass imageClass : errors.keySet()) {
                AngrySquiggleHighlighter highlighter = new AngrySquiggleHighlighter(mainGUI.getMain(), imageClass, 3, imageClass.getHighlightedFile(), imageClass.getScannedImage(), errors.get(imageClass));
                highlighter.highlightAngrySquiggles();
            }

        } catch (TranscoderException | ExecutionException | InterruptedException e) {
            e.printStackTrace();
        } finally {
            Optional<Map.Entry<ImageClass, List<LanguageError>>> firstEntry = errors != null ? errors.entrySet().stream().findFirst() : Optional.empty();
            String append = "";
            if (firstEntry.isPresent()) {
                append += " With ";
                List<LanguageError> languageErrors = firstEntry.get().getValue();
                if (languageErrors.size() > 1) {
                    append += languageErrors.size() + " errors";
                } else {
                    append += "an error (" + languageErrors.get(0).getMessage() + " in " + firstEntry.get().getKey().getInputImage().getPath() + ")";
                }

                append += ". See compiler output image for details";
            }

            LOGGER.info("Finished " + (getCurrentLanguage().isInterpreted() ? "interpreting" : "compiling") + " in " + (System.currentTimeMillis() - start) + "ms" + append);

            LOGGER.info("Saving output images...");
            mainGUI.setStatusText("Saving output images...");

            imageOutputStream.saveImage();
            compilerOutputStream.saveImage();

            mainGUI.setStatusText(null);
        }

        imageClasses.clear();
    }

    public static List<File> getFilesFromDirectory(File directory, String extension) {
        return getFilesFromDirectory(directory, new String[] {extension});
    }

    public static List<File> getFilesFromDirectory(File directory, String[] extensions) {
        List<File> ret = new ArrayList<>();
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                ret.addAll(getFilesFromDirectory(file, extensions));
            } else {
                if (extensions == null || Arrays.stream(extensions).anyMatch(extension -> file.getName().endsWith("." + extension))) ret.add(file);
            }
        }

        return ret;
    }

    public List<File> getFilesFromDirectory(File directory, String[] extensions, String postExtension) {
        return getFilesFromDirectory(directory, Arrays.stream(extensions).map(string -> string + "." + postExtension).toArray(String[]::new));
    }

    public void setInputImage(File inputImage) {
        PPFProject ppfProject = ProjectManager.getPPFProject();
        if (Objects.equals(inputImage, ppfProject.getInputLocation())) return;
        ProjectManager.getPPFProject().setInputLocation(inputImage);

        File outputParent = inputImage.getParentFile();

        File file = this.currentLanguage.getOutputFileExtension() == null ? null : new File(outputParent, "Output." + this.currentLanguage.getOutputFileExtension());

        ppfProject.setHighlightLocation(new File(outputParent, "highlighted"), false);
        ppfProject.setCompilerOutput(new File(outputParent, "compiler.png"), false);
        ppfProject.setAppOutput(new File(outputParent, "program.png"), false);
        ppfProject.setJarFile(file, false);
        ppfProject.setClassLocation(new File(outputParent, "classes"), false);

        ProjectManager.save();
        this.mainGUI.initializeInputTextFields();
    }

    public void addPath(String path) {
        try {
            if (this.added.contains(path)) return;
            if (this.addURL == null) {
                this.addURL = ClassLoader.getSystemClassLoader().getClass().getDeclaredMethod("appendToClassPathForInstrumentation", String.class);
                this.addURL.setAccessible(true);
            }

            this.addURL.invoke(ClassLoader.getSystemClassLoader(), path);
            this.added.add(path);
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
        }
    }

    public static Optional<File> getCurrentJar() {
        if (currentJar != null) return Optional.of(currentJar);
        try {
            return Optional.of((currentJar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath())));
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public static Optional<File> getJarParent() {
        try {
            var currentJar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
            var numBack = currentJar.getParentFile().getName().equals("app") ? 2 : 4;
            return Optional.of(getParentsBack(currentJar, numBack));
        } catch (URISyntaxException e) {
            LOGGER.error("Error getting URI of file", e);
        }

        return Optional.empty();
    }

    public static File getParentsBack(File base, int back) {
        for (int i = 0; i < back; i++) base = base.getParentFile();
        return base;
    }

    public OCRManager getOCRManager() {
        return ocrManager;
    }

    public LanguageManager getLanguageManager() {
        return this.languageManager;
    }

    public RunningCodeManager getRunningCodeManager() {
        return runningCodeManager;
    }

    public CenterPopulator getCenterPopulator() {
        return centerPopulator;
    }

    public String getFontName() {
        return MainGUI.HEADLESS ? SettingsManager.getInstance().getSetting(Setting.HEADLESS_FONT) : ProjectManager.getPPFProject().getActiveFont();
    }

    public String getFontConfig() {
        return MainGUI.HEADLESS ? SettingsManager.getInstance().getSetting(Setting.HEADLESS_FONT_CONFIG) : ProjectManager.getPPFProject().getActiveFontConfig();
    }
}
