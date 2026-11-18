package dev.gatehouse.media;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
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
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The chat's video and sound player (R62), a window of its own: play/pause, a seek bar with the time, back and forward
 * ten seconds, mute and volume, theater (a large window) and full screen. Keys: Space or K play/pause, Left/Right or
 * J/L ten seconds, Up/Down volume, M mute, T theater, F full screen, Esc leaves full screen. A click on the picture
 * plays or pauses it; a double click is full screen.
 *
 * Built against OpenJFX's base, graphics and media parts only (its buttons and sliders are drawn here, so devices fetch
 * less), and shipped inside the program as media-player.jar; dev.gatehouse.look.MediaEngine loads it, with the parts it
 * fetched once, in a class loader of their own, and calls open() by name.
 */
public final class Player {
	private Player() {}
	private static final AtomicBoolean started = new AtomicBoolean();
	private static final Color BG = Color.web("#121b28"), BAR = Color.web("#1b2838"), KEY = Color.web("#2b3d52"), KEY_LIT = Color.web("#3a5370"),
			TEXT = Color.web("#e6edf5"), MUTED = Color.web("#9fb0c3"), ACCENT = Color.web("#5bb8f0"), TRACK = Color.web("#33475e");

	/** Opens a player window for a file; failed runs when this file can't be played here (the caller opens it otherwise). */
	public static void open(Path file, String title, boolean video, Runnable failed) throws Exception {
		if (started.compareAndSet(false, true)) {
			java.util.concurrent.CountDownLatch up = new java.util.concurrent.CountDownLatch(1);
			Platform.startup(up::countDown);
			Platform.setImplicitExit(false);
			if (!up.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("the player didn't start");
		}
		Platform.runLater(() -> {
			try { show(file, title, video, failed); }
			catch (RuntimeException e) { failed.run(); }
		});
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
			label = text(words, 14, TEXT);
			Rectangle back = new Rectangle(); back.setArcWidth(10); back.setArcHeight(10); back.setFill(KEY);
			getChildren().addAll(back, label);
			setPadding(new Insets(6, 12, 6, 12));
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
			setPrefSize(width, 22); setMinSize(60, 22); setMaxHeight(22);
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

	private static void show(Path file, String title, boolean video, Runnable failed) {
		Media media = new Media(file.toUri().toString());
		MediaPlayer mp = new MediaPlayer(media);
		Stage st = new Stage();
		st.setTitle(title + " — Gatehouse player");

		// what is shown: the picture, or for a sound its name on a quiet background
		StackPane screen = new StackPane();
		screen.setStyle("-fx-background-color: " + (video ? "black" : "#121b28") + ";");
		Text problem = text("", 15, TEXT);
		problem.setTextAlignment(TextAlignment.CENTER);
		problem.wrappingWidthProperty().bind(screen.widthProperty().subtract(40));
		problem.setVisible(false);
		if (video) {
			MediaView mv = new MediaView(mp);
			mv.setPreserveRatio(true);
			mv.fitWidthProperty().bind(screen.widthProperty());
			mv.fitHeightProperty().bind(screen.heightProperty());
			screen.getChildren().add(mv);
		} else {
			VBox v = new VBox(8, text("♫", 64, ACCENT), text(title, 17, TEXT));
			v.setAlignment(Pos.CENTER);
			screen.getChildren().add(v);
		}
		screen.getChildren().add(problem);

		// the controls
		Runnable toggle = () -> { if (mp.getStatus() == MediaPlayer.Status.PLAYING) mp.pause(); else mp.play(); };
		Runnable bk = () -> mp.seek(Duration.seconds(Math.max(0, mp.getCurrentTime().toSeconds() - 10)));
		Runnable fw = () -> mp.seek(Duration.seconds(Math.min(media.getDuration().toSeconds(), mp.getCurrentTime().toSeconds() + 10)));
		Rectangle2D[] before = {null};
		Key[] th = new Key[1], fs = new Key[1];
		Runnable theater = () -> {
			if (st.isFullScreen()) st.setFullScreen(false);
			if (before[0] == null) {
				before[0] = new Rectangle2D(st.getX(), st.getY(), st.getWidth(), st.getHeight());
				Rectangle2D s = Screen.getScreensForRectangle(before[0]).stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
				st.setX(s.getMinX() + s.getWidth() * 0.04); st.setY(s.getMinY() + s.getHeight() * 0.04);
				st.setWidth(s.getWidth() * 0.92); st.setHeight(s.getHeight() * 0.92);
				th[0].say("Smaller");
			} else {
				st.setX(before[0].getMinX()); st.setY(before[0].getMinY()); st.setWidth(before[0].getWidth()); st.setHeight(before[0].getHeight());
				before[0] = null; th[0].say("Theater");
			}
		};
		Runnable full = () -> st.setFullScreen(!st.isFullScreen());
		Key play = new Key("Pause", toggle), back = new Key("- 10 s", bk), fwd = new Key("+ 10 s", fw);
		Key mute = new Key("Mute", () -> mp.setMute(!mp.isMute()));
		th[0] = new Key("Theater", theater); fs[0] = new Key("Full screen", full);
		Bar seek = new Bar(240, v -> mp.seek(Duration.seconds(v))), vol = new Bar(80, v -> {});
		vol.value.set(0.8);
		HBox.setHgrow(seek, Priority.ALWAYS);
		Text at = text("0:00 / 0:00", 13, MUTED);
		HBox bar = new HBox(8, play, back, fwd, seek, at, mute, vol, th[0], fs[0]);
		bar.setAlignment(Pos.CENTER_LEFT);
		bar.setPadding(new Insets(8, 10, 8, 10));
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
		st.fullScreenProperty().addListener((o, a, b) -> fs[0].say(b ? "Leave full screen" : "Full screen"));
		screen.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && video) full.run(); else if (e.getClickCount() == 1) toggle.run(); });

		Runnable cant = () -> {
			String why = mp.getError() != null ? mp.getError().getMessage() : media.getError() != null ? media.getError().getMessage() : "";
			problem.setText("This can't be played here" + (why == null || why.isEmpty() ? "." : ": " + why) + "\nIt opens with this device's own program instead.");
			problem.setVisible(true);
			failed.run();
		};
		mp.setOnError(cant);
		media.setOnError(cant);

		BorderPane root = new BorderPane(screen);
		root.setBottom(bar);
		Scene sc = new Scene(root, video ? 960 : 720, video ? 600 : 260, BG);
		sc.setOnKeyPressed(e -> {
			KeyCode k = e.getCode();
			if (k == KeyCode.SPACE || k == KeyCode.K) toggle.run();
			else if (k == KeyCode.LEFT || k == KeyCode.J) bk.run();
			else if (k == KeyCode.RIGHT || k == KeyCode.L) fw.run();
			else if (k == KeyCode.UP) vol.value.set(Math.min(1, vol.value.get() + 0.1));
			else if (k == KeyCode.DOWN) vol.value.set(Math.max(0, vol.value.get() - 0.1));
			else if (k == KeyCode.M) mp.setMute(!mp.isMute());
			else if (k == KeyCode.T) theater.run();
			else if (k == KeyCode.F) full.run();
			else return;
			e.consume();
		});
		st.setScene(sc);
		st.setMinWidth(560); st.setMinHeight(200);
		st.setOnHidden(e -> mp.dispose());
		st.show();
		st.toFront();
	}
}
