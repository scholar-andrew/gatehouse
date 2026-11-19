package dev.gatehouse.media;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * The chat's video and sound player (R62), shown inside the chat message itself (no window of its own: a second window
 * never appeared in a handheld's game mode, and the owner wanted media in the chat). Play/pause, a seek bar with the
 * time, back and forward ten seconds, mute and volume, Theater (the player as wide as the chat and taller) and Full
 * screen (the player alone on the screen; Esc or the button brings it back into the chat). Keys while it has focus:
 * Space or K, Left/Right or J/L, Up/Down, M, T, F, Esc. A click on the picture plays or pauses; a double click is full
 * screen.
 *
 * It is a JFXPanel (OpenJFX's Swing bridge) holding a JavaFX scene, so it sits in Gatehouse's and Gatekeeper's Swing
 * chat like any other component. Built against OpenJFX's base, graphics, media and swing parts (its buttons and sliders
 * are drawn here), shipped inside the program as media-player.jar, and loaded by dev.gatehouse.look.MediaEngine with
 * the parts it fetched, in a class loader of their own; MediaEngine calls embed() by name.
 */
public final class Player {
	private Player() {}
	private static final Color BG = Color.web("#121b28"), KEY = Color.web("#2b3d52"), KEY_LIT = Color.web("#3a5370"),
			TEXT = Color.web("#e6edf5"), MUTED = Color.web("#9fb0c3"), ACCENT = Color.web("#5bb8f0"), TRACK = Color.web("#33475e");
	private static final int W = 480, VIDEO_H = 300, SOUND_H = 96;

	/**
	 * A player for a file, to put in the chat (call on Swing's thread). problem gets a sentence when the file can't be
	 * played here (on Swing's thread); the caller shows it and offers the device's own program.
	 */
	public static Component embed(Path file, String title, boolean video, Consumer<String> problem) {
		JFXPanel panel = new JFXPanel();   // (starts JavaFX the first time)
		Platform.setImplicitExit(false);
		Dimension small = new Dimension(W, video ? VIDEO_H : SOUND_H);
		panel.setPreferredSize(small);
		panel.setMaximumSize(small);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		MediaPlayer[] holder = new MediaPlayer[1];
		Platform.runLater(() -> {
			try { holder[0] = build(panel, file, title, video, problem, small); }
			catch (RuntimeException e) { SwingUtilities.invokeLater(() -> problem.accept(why(e))); }
		});
		// (stops and frees the player when the message leaves the screen for good: another room, the window closed)
		panel.addHierarchyListener(e -> {
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 && !panel.isDisplayable() && !Boolean.TRUE.equals(panel.getClientProperty("moving")))
				Platform.runLater(() -> { if (holder[0] != null) holder[0].dispose(); });
		});
		return panel;
	}

	private static String why(Throwable e) {
		String m = e.getMessage();
		return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
	}
	/** A media failure in plain words. */
	private static String plain(javafx.scene.media.MediaException e) {
		if (e == null) return "the player stopped";
		return switch (e.getType()) {
			case MEDIA_CORRUPTED, MEDIA_INACCESSIBLE -> "the file isn't one this player can read";
			case MEDIA_UNSUPPORTED, MEDIA_UNAVAILABLE, MEDIA_UNSPECIFIED -> "this device's player can't play this kind of video or sound"
					+ (System.getProperty("os.name", "").toLowerCase().startsWith("linux") ? " (on Linux, video needs the system's ffmpeg libraries)" : "");
			case OPERATION_UNSUPPORTED -> "this device can't do that with this file";
			default -> "the player stopped (" + e.getType() + ")";
		};
	}
	private static String time(Duration d) {
		if (d == null || d.isUnknown() || d.isIndefinite()) return "0:00";
		long s = (long) Math.floor(d.toSeconds());
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}
	private static Text text(String s, double size, Color c) { Text t = new Text(s); t.setFont(Font.font(size)); t.setFill(c); return t; }

	/** A button: words on a rounded key. */
	private static final class Key extends StackPane {
		final Text label;
		Key(String words, Runnable run) {
			label = text(words, 13, TEXT);
			Rectangle back = new Rectangle(); back.setArcWidth(10); back.setArcHeight(10); back.setFill(KEY);
			getChildren().addAll(back, label);
			setPadding(new Insets(5, 10, 5, 10));
			back.widthProperty().bind(widthProperty()); back.heightProperty().bind(heightProperty());
			back.setManaged(false);
			setCursor(Cursor.HAND);
			setOnMouseEntered(e -> back.setFill(KEY_LIT));
			setOnMouseExited(e -> back.setFill(KEY));
			setOnMouseClicked(e -> run.run());
		}
		void say(String s) { label.setText(s); }
	}
	/** A slider: a track, the part filled, and a knob; dragging or clicking sets it (0..max). */
	private static final class Bar extends Pane {
		final SimpleDoubleProperty value = new SimpleDoubleProperty(), max = new SimpleDoubleProperty(1);
		boolean held;
		Bar(double width, DoubleConsumer set) {
			Rectangle track = new Rectangle(0, 8, width, 6), fill = new Rectangle(0, 8, 0, 6), knob = new Rectangle(0, 3, 4, 16);
			track.setArcWidth(6); track.setArcHeight(6); track.setFill(TRACK);
			fill.setArcWidth(6); fill.setArcHeight(6); fill.setFill(ACCENT);
			knob.setArcWidth(4); knob.setArcHeight(4); knob.setFill(TEXT);
			getChildren().addAll(track, fill, knob);
			setPrefSize(width, 22); setMinSize(50, 22); setMaxHeight(22);
			track.widthProperty().bind(widthProperty());
			Runnable draw = () -> {
				double f = max.get() <= 0 ? 0 : Math.max(0, Math.min(1, value.get() / max.get())), w = getWidth() <= 0 ? width : getWidth();
				fill.setWidth(f * w); knob.setX(Math.max(0, f * w - 2));
			};
			value.addListener((o, a, b) -> draw.run()); max.addListener((o, a, b) -> draw.run()); widthProperty().addListener((o, a, b) -> draw.run());
			java.util.function.Consumer<MouseEvent> at = e -> {
				double w = getWidth() <= 0 ? width : getWidth();
				value.set(Math.max(0, Math.min(1, e.getX() / w)) * max.get());
			};
			setCursor(Cursor.HAND);
			setOnMousePressed(e -> { held = true; at.accept(e); });
			setOnMouseDragged(e -> at.accept(e));
			setOnMouseReleased(e -> { at.accept(e); held = false; set.accept(value.get()); });
		}
	}

	/** What native code (the sound library) sees for a setting: through dev.gatehouse.look.NativeEnv, by name. */
	private static String nativeEnv(String name) {
		try { return String.valueOf(Class.forName("dev.gatehouse.look.NativeEnv", true, Player.class.getClassLoader().getParent()).getMethod("get", String.class).invoke(null, name)); }
		catch (Throwable t) { return System.getenv(name) + " (start)"; }
	}
	/** R80: how a video plays here, measured once after ten seconds of playing, for the engine's log and the owner's. */
	private static void measure(MediaPlayer mp, Media media, String title, int[] stalls) {
		long[] start = {0}; double[] from = {0}; boolean[] done = {false};
		mp.statusProperty().addListener((o, was, now) -> {
			if (now == MediaPlayer.Status.PLAYING && start[0] == 0) {
				start[0] = System.nanoTime(); from[0] = mp.getCurrentTime().toSeconds();
				javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(Duration.seconds(10));
				wait.setOnFinished(e -> {
					if (done[0] || mp.getStatus() != MediaPlayer.Status.PLAYING) return;
					done[0] = true;
					double wall = (System.nanoTime() - start[0]) / 1e9, played = mp.getCurrentTime().toSeconds() - from[0];
					String pipe = "?";
					try { Object p = Class.forName("com.sun.prism.GraphicsPipeline").getMethod("getPipeline").invoke(null); pipe = p == null ? "none" : p.getClass().getSimpleName(); } catch (Throwable t) { pipe = "unknown"; }
					StringBuilder tracks = new StringBuilder();
					for (javafx.scene.media.Track t : media.getTracks()) tracks.append(tracks.length() == 0 ? "" : ", ").append(t.getClass().getSimpleName().replace("Track", "")).append(' ').append(t.getMetadata().getOrDefault("encoding", ""));
					String line = "media playback: " + title.replaceAll(".*\\.", ".") + " " + media.getWidth() + "x" + media.getHeight()
							+ String.format(java.util.Locale.ROOT, ", played %.1f s of it in %.1f s (%.0f%% speed)", played, wall, wall <= 0 ? 0 : 100 * played / wall)
							+ ", stalls " + stalls[0] + ", drawing " + pipe + ", pulse " + System.getProperty("javafx.animation.pulse", "60")
							+ ", tracks [" + tracks + "], java " + System.getProperty("java.version") + " (" + System.getProperty("java.home", "").replaceAll(".*[/\\\\](?=[^/\\\\]+$)", "") + ")"
							+ ", " + System.getProperty("os.name") + " " + System.getProperty("os.version")
							+ ", flatpak " + (System.getenv("FLATPAK_ID") == null ? "no" : System.getenv("FLATPAK_ID"))
							+ ", PIPEWIRE_LATENCY " + nativeEnv("PIPEWIRE_LATENCY") + ", PULSE_LATENCY_MSEC " + nativeEnv("PULSE_LATENCY_MSEC")
							+ ", XDG_SESSION " + System.getenv("XDG_SESSION_TYPE") + ", gamescope " + (System.getenv("GAMESCOPE_WAYLAND_DISPLAY") != null || System.getenv("SteamGamepadUI") != null);
					try { Class.forName("dev.gatehouse.look.MediaEngine", true, Player.class.getClassLoader().getParent()).getMethod("report", String.class).invoke(null, line); }
					catch (Throwable t) { System.err.println(line); }
				});
				wait.play();
			}
		});
		mp.setOnStalled(() -> stalls[0]++);
	}

	/** The scene, on JavaFX's thread. */
	private static MediaPlayer build(JFXPanel panel, Path file, String title, boolean video, Consumer<String> problem, Dimension small) {
		Media media = new Media(file.toUri().toString());
		MediaPlayer mp = new MediaPlayer(media);
		measure(mp, media, title, new int[] {0});   // (R80)

		StackPane screen = new StackPane();
		screen.setStyle("-fx-background-color: " + (video ? "black" : "#121b28") + ";");
		if (video) {
			MediaView mv = new MediaView(mp);
			mv.setPreserveRatio(true);
			// (R80: on Linux, scaling each frame smoothly cost the processor more than it could spare on a Steam Deck)
			if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux")) mv.setSmooth(false);
			mv.fitWidthProperty().bind(screen.widthProperty());
			mv.fitHeightProperty().bind(screen.heightProperty());
			screen.getChildren().add(mv);
		} else {
			Text name = text("♫  " + title, 14, TEXT);
			screen.getChildren().add(name);
			StackPane.setAlignment(name, Pos.CENTER_LEFT);
			StackPane.setMargin(name, new Insets(0, 12, 0, 12));
		}
		Text trouble = text("", 13, TEXT);
		trouble.setTextAlignment(TextAlignment.CENTER);
		trouble.wrappingWidthProperty().bind(screen.widthProperty().subtract(24));
		trouble.setVisible(false);
		screen.getChildren().add(trouble);

		// Theater and full screen move the Swing side; the scene just follows its size.
		boolean[] wide = {false}, full = {false};
		Key[] th = new Key[1], fs = new Key[1];
		JFrame[] fullFrame = {null};
		Container[] home = {null};
		int[] homeIndex = {-1};
		Runnable toggle = () -> { if (mp.getStatus() == MediaPlayer.Status.PLAYING) mp.pause(); else mp.play(); };
		Runnable bk = () -> mp.seek(Duration.seconds(Math.max(0, mp.getCurrentTime().toSeconds() - 10)));
		Runnable fw = () -> mp.seek(Duration.seconds(Math.min(media.getDuration().toSeconds(), mp.getCurrentTime().toSeconds() + 10)));
		Runnable theater = () -> SwingUtilities.invokeLater(() -> {
			if (full[0]) return;
			wide[0] = !wide[0];
			Container chat = panel.getParent();
			while (chat != null && !(chat instanceof javax.swing.JViewport)) chat = chat.getParent();
			int w = chat == null ? W * 2 : Math.max(W, chat.getWidth() - 40);
			Dimension d = wide[0] ? new Dimension(w, video ? Math.max(VIDEO_H, (int) (w * 9 / 16.0) + 40) : SOUND_H) : small;
			panel.setPreferredSize(d); panel.setMaximumSize(d);
			panel.revalidate(); if (panel.getParent() != null) panel.getParent().revalidate();
			panel.scrollRectToVisible(new java.awt.Rectangle(0, 0, d.width, d.height));
			Platform.runLater(() -> th[0].say(wide[0] ? "Smaller" : "Theater"));
		});
		Runnable fullScreen = () -> SwingUtilities.invokeLater(() -> {
			java.awt.GraphicsDevice dev = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
			panel.putClientProperty("moving", Boolean.TRUE);
			try {
				if (!full[0]) {
					home[0] = panel.getParent();
					homeIndex[0] = home[0] == null ? -1 : java.util.Arrays.asList(home[0].getComponents()).indexOf(panel);
					JFrame f = new JFrame(title);
					f.setUndecorated(true);
					f.getContentPane().setBackground(java.awt.Color.BLACK);
					f.getContentPane().add(panel, BorderLayout.CENTER);
					panel.setMaximumSize(null);
					java.awt.Rectangle b = dev.getDefaultConfiguration().getBounds();
					f.setBounds(b);
					if (dev.isFullScreenSupported()) dev.setFullScreenWindow(f); else { f.setExtendedState(JFrame.MAXIMIZED_BOTH); f.setVisible(true); }
					fullFrame[0] = f; full[0] = true;
					panel.requestFocusInWindow();
				} else {
					JFrame f = fullFrame[0];
					if (dev.getFullScreenWindow() == f) dev.setFullScreenWindow(null);
					f.getContentPane().remove(panel);
					f.dispose();
					Dimension d = panel.getPreferredSize();
					panel.setMaximumSize(d);
					if (home[0] != null) { home[0].add(panel, Math.min(homeIndex[0] < 0 ? home[0].getComponentCount() : homeIndex[0], home[0].getComponentCount())); home[0].revalidate(); home[0].repaint(); }
					full[0] = false; fullFrame[0] = null;
				}
			} finally { panel.putClientProperty("moving", null); }
			Platform.runLater(() -> fs[0].say(full[0] ? "Leave full screen" : "Full screen"));
		});

		Key play = new Key("Pause", toggle), back = new Key("- 10 s", bk), fwd = new Key("+ 10 s", fw);
		Key mute = new Key("Mute", () -> mp.setMute(!mp.isMute()));
		th[0] = new Key("Theater", theater); fs[0] = new Key("Full screen", fullScreen);
		Bar seek = new Bar(160, v -> mp.seek(Duration.seconds(v))), vol = new Bar(60, v -> {});
		vol.value.set(0.8);
		HBox.setHgrow(seek, Priority.ALWAYS);
		Text at = text("0:00 / 0:00", 12, MUTED);
		HBox bar = new HBox(6, play, back, fwd, seek, at, mute, vol);
		if (video) bar.getChildren().addAll(th[0], fs[0]);
		bar.setAlignment(Pos.CENTER_LEFT);
		bar.setPadding(new Insets(6, 8, 6, 8));
		bar.setStyle("-fx-background-color: #1b2838;");

		mp.setVolume(vol.value.get());
		vol.value.addListener((o, a, b) -> { mp.setVolume(b.doubleValue()); if (b.doubleValue() > 0) mp.setMute(false); });
		mp.muteProperty().addListener((o, a, b) -> mute.say(b ? "Unmute" : "Mute"));
		mp.currentTimeProperty().addListener((o, a, b) -> {
			if (!seek.held) seek.value.set(b.toSeconds());
			at.setText(time(b) + " / " + time(mp.getTotalDuration()));
		});
		mp.setOnReady(() -> { Duration d = media.getDuration(); seek.max.set(Math.max(1, d.toSeconds())); at.setText("0:00 / " + time(d)); mp.play(); });
		mp.statusProperty().addListener((o, a, b) -> play.say(b == MediaPlayer.Status.PLAYING ? "Pause" : "Play"));
		mp.setOnEndOfMedia(() -> { mp.pause(); mp.seek(Duration.ZERO); });
		screen.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && video) fullScreen.run(); else if (e.getClickCount() == 1) toggle.run(); });

		boolean[] told = {false};
		Runnable cant = () -> {
			if (told[0]) return;   // (the media and the player both report the same failure)
			told[0] = true;
			javafx.scene.media.MediaException e = mp.getError() != null ? mp.getError() : media.getError();
			String why = plain(e);
			// (the chat says it under the message, with a way to open it elsewhere; the empty player is hidden)
			SwingUtilities.invokeLater(() -> {
				panel.setVisible(false);
				if (panel.getParent() != null) panel.getParent().revalidate();
				problem.accept(why + (e == null ? "" : " [" + e.getType() + "]"));
			});
		};
		mp.setOnError(cant);
		media.setOnError(cant);

		BorderPane root = new BorderPane(screen);
		root.setBottom(bar);
		Scene sc = new Scene((Parent) root, BG);
		sc.setOnKeyPressed(e -> {
			KeyCode k = e.getCode();
			if (k == KeyCode.SPACE || k == KeyCode.K) toggle.run();
			else if (k == KeyCode.LEFT || k == KeyCode.J) bk.run();
			else if (k == KeyCode.RIGHT || k == KeyCode.L) fw.run();
			else if (k == KeyCode.UP) vol.value.set(Math.min(1, vol.value.get() + 0.1));
			else if (k == KeyCode.DOWN) vol.value.set(Math.max(0, vol.value.get() - 0.1));
			else if (k == KeyCode.M) mp.setMute(!mp.isMute());
			else if (k == KeyCode.T && video) theater.run();
			else if (k == KeyCode.F && video) fullScreen.run();
			else if (k == KeyCode.ESCAPE && full[0]) fullScreen.run();
			else return;
			e.consume();
		});
		panel.setScene(sc);
		return mp;
	}
}
