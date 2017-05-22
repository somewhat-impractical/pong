package net.darktrojan.pong;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

public class MySurfaceView extends SurfaceView implements SurfaceHolder.Callback {

	final SurfaceHolder surfaceHolder;
	final SharedPreferences preferences;

	MyThread thread = null;
	float previousTouchX;
	float previousTouchY;

	final float RADIANS_TO_DEGREES = 180 / (float) Math.PI;
	final float INNER_RADIUS = 290;
	final float DEATH_RADIUS = 330;

	final float BALL_RADIUS = 15;

	RectF canvasSize;
	float canvasScale = 1;
	RectF canvasSizeScaled, canvasSizeScaledTranslated;

	int score, bestScore;
	boolean running = false;
	boolean died = false;
	boolean readyToRestart = false;

	Paint backgroundPaint, paddlePaint, ballPaint, scorePaint;
	Drawable plus10Image, plus100Image, times2Image, times3Image, speedUpImage;

	float paddleAngle;

	PointF ballPosition;
	float ballAngle;
	float ballSpeed;
	PointF ballDelta;
	int multiplier = 1;

	String currentTarget;
	PointF targetPosition;
	List<AbstractBonus> bonuses = new ArrayList<>();

	MediaPlayer targetHitSound;

	Typeface typeface;

	public MySurfaceView(Context context, AttributeSet attrs) {
		super(context, attrs);

		surfaceHolder = getHolder();
		surfaceHolder.addCallback(this);

		preferences = context.getSharedPreferences("bestScore", Context.MODE_PRIVATE);
	}

	@Override
	public boolean onTouchEvent(@NonNull MotionEvent e) {
		float touchX = e.getX() - getWidth() / 2;
		float touchY = e.getY() - getHeight() / 2;

		switch (e.getAction()) {
			case MotionEvent.ACTION_DOWN:
				if (readyToRestart) {
					reset();
				}
				break;
			case MotionEvent.ACTION_MOVE:
				double oldAngle = Math.atan(previousTouchY / previousTouchX);
				double newAngle = Math.atan(touchY / touchX);
				double dAngle = newAngle - oldAngle;
				if ((touchX < 0 && previousTouchX > 0) || (touchX > 0 && previousTouchX < 0)) {
					dAngle += Math.PI;
				}
				paddleAngle = paddleAngle + (float) dAngle;
				break;
		}

		previousTouchY = touchY;
		previousTouchX = touchX;
		return true;
	}

	public void surfaceCreated(SurfaceHolder holder) {
		if (thread == null || thread.getState() != Thread.State.NEW) {
			thread = new MyThread(getContext());
		}
		this.running = true;
		thread.start();
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		canvasSize = new RectF(0, 0, width, height);
		int narrowest = Math.min(width, height);
		canvasScale = (float) narrowest / 720;
		canvasSizeScaled = new RectF(0, 0, width / canvasScale, height / canvasScale);
		canvasSizeScaledTranslated = new RectF(0, 0, canvasSizeScaled.right + BALL_RADIUS * 2, canvasSizeScaled.bottom + BALL_RADIUS * 2);
		canvasSizeScaledTranslated.offset(canvasSizeScaledTranslated.right / -2, canvasSizeScaledTranslated.bottom / -2);
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		running = false;
		targetHitSound.release();

		boolean retry = true;
		do {
			try {
				thread.join();
				retry = false;
			} catch (InterruptedException e) {
				// Do nothing.
			}
		} while (retry);
	}

	public void reset() {
		score = 0;
		paddleAngle = 0;
		ballPosition = new PointF(0, 0);
		ballAngle = 0;
		ballSpeed = 5;
		ballDelta = new PointF(ballSpeed, 0);
		multiplier = 1;
		targetPosition = new PointF(100, 50);
		currentTarget = "plus10";
		bonuses.clear();

		running = true;
		died = false;
		readyToRestart = false;
	}

	void realGameLoop(Canvas c) {
		paddleAngle = clampAngle(paddleAngle);

		c.drawRect(canvasSize, backgroundPaint);
		c.scale(canvasScale, canvasScale);

		if (canvasSizeScaledTranslated.contains(ballPosition.x, ballPosition.y)) {
			ballPosition.offset(ballDelta.x, ballDelta.y);
			if (!died) {
				gameLoop();
			}

			scorePaint.setTextAlign(Paint.Align.RIGHT);
			drawScore(c);
			drawMultipliers(c);
			drawGame(c);
		} else {
			if (score > bestScore) {
				bestScore = score;
				SharedPreferences.Editor editor = preferences.edit();
				editor.putInt("bestScore", bestScore);
				editor.apply();
			}

			scorePaint.setTextAlign(Paint.Align.CENTER);
			c.drawText("Game Over", canvasSizeScaled.centerX(), canvasSizeScaled.centerY() - 35, scorePaint);
			c.drawText("Score " + Integer.toString(score), canvasSizeScaled.centerX(), canvasSizeScaled.centerY() + 35, scorePaint);
			c.drawText("Tap to Restart", canvasSizeScaled.centerX(), canvasSizeScaled.bottom - 140, scorePaint);
			readyToRestart = true;
		}
	}

	void gameLoop() {
		long now = System.currentTimeMillis();

		ListIterator<AbstractBonus> li = bonuses.listIterator();
		while (li.hasNext()) {
			AbstractBonus m = li.next();
			if (now > m.expires) {
				li.remove();
				m.remove();
			}
		}


		if (PointF.length(ballPosition.x - targetPosition.x, ballPosition.y - targetPosition.y) < 36) {
			switch (currentTarget) {
				case "plus10":
					score = score + 10 * multiplier;
					break;
				case "plus100":
					score = score + 100 * multiplier;
					break;
				case "times2":
					bonuses.add(new MultiplierBonus(2));
					break;
				case "times3":
					bonuses.add(new MultiplierBonus(3));
					break;
				case "speedUp":
					bonuses.add(new SpeedUpBonus());
					break;
			}

			if (targetHitSound.isPlaying()) {
				targetHitSound.seekTo(0);
			}
			targetHitSound.start();

			targetPosition.x = (float) (Math.random() * 250) - 125;
			targetPosition.y = (float) (Math.random() * 250) - 125;

			double random = Math.random();
			if (random > 0.9) {
				currentTarget = "speedUp";
			} else if (random > 0.8) {
				currentTarget = "times3";
			} else if (random > 0.55) {
				currentTarget = "times2";
			} else if (random > 0.45) {
				currentTarget = "plus100";
			} else {
				currentTarget = "plus10";
			}
		}

		PointF distanceFromPaddle = getDistanceFromPaddle();
		if (distanceFromPaddle.y > 0) {
			if (Math.abs(distanceFromPaddle.x) < 125) {
				bounce(distanceFromPaddle);
				score = score + multiplier;
			} else {
				died = true;
			}
		}

		if (ballPosition.length() > DEATH_RADIUS) {
			died = true;
		}
	}

	void bounce(PointF distanceFromPaddle) {
		ballPosition.x -= 2 * distanceFromPaddle.y * Math.cos(paddleAngle);
		ballPosition.y -= 2 * distanceFromPaddle.y * Math.sin(paddleAngle);

		float normal = ballAngle - paddleAngle;
		ballAngle -= Math.PI + normal * 2;
		ballAngle = clampAngle(ballAngle);
		calculateBallDelta();

//			bounceSound.start();
	}

	private void calculateBallDelta() {
		ballDelta.x = (float) (ballSpeed * Math.cos(ballAngle));
		ballDelta.y = (float) (ballSpeed * Math.sin(ballAngle));
	}

	float clampAngle(float angle) {
		while (angle < -Math.PI) {
//				Log.v(VIEW_LOG_TAG, "angle is " + Float.toString(angle));
			angle += Math.PI * 2;
		}
		while (angle > Math.PI) {
//				Log.v(VIEW_LOG_TAG, "angle is " + Float.toString(angle));
			angle -= Math.PI * 2;
		}
		return angle;
	}

	void drawScore(Canvas c) {
		c.drawText("Score " + Integer.toString(score), canvasSizeScaled.right, canvasSizeScaled.top + 50, scorePaint);
		c.drawText("Best " + Integer.toString(bestScore), canvasSizeScaled.right, canvasSizeScaled.top + 120, scorePaint);
	}

	void drawMultipliers(Canvas c) {
		c.save();
		c.translate(40, 30);
		for (AbstractBonus b : bonuses) {
			b.image.draw(c);
			c.translate(40, 0);
		}
		c.restore();
	}

	void drawGame(Canvas c) {
		c.translate(canvasSizeScaled.centerX(), canvasSizeScaled.centerY());
		c.save();
		c.translate(ballPosition.x, ballPosition.y);
		c.drawCircle(0, 0, BALL_RADIUS, ballPaint);
		c.restore();
		c.save();
		c.rotate(paddleAngle * RADIANS_TO_DEGREES);
		c.drawRect(INNER_RADIUS + BALL_RADIUS, -125, INNER_RADIUS + 35, 125, paddlePaint);
		c.restore();
		c.save();
		c.translate(targetPosition.x, targetPosition.y);
		switch (currentTarget) {
			case "plus10":
				plus10Image.draw(c);
				break;
			case "plus100":
				plus100Image.draw(c);
				break;
			case "times2":
				times2Image.draw(c);
				break;
			case "times3":
				times3Image.draw(c);
				break;
			case "speedUp":
				speedUpImage.draw(c);
				break;
		}
		c.restore();
	}

	PointF getCenterOfPaddle() {
		return new PointF(
				(float) Math.cos(paddleAngle) * INNER_RADIUS,
				(float) Math.sin(paddleAngle) * INNER_RADIUS
		);
	}

	PointF getDistanceFromPaddle() {
		PointF centerOfPaddle = getCenterOfPaddle();
		float x = ballPosition.x - centerOfPaddle.x;
		float y = ballPosition.y - centerOfPaddle.y;
		double a = Math.atan(y / x);
		double b = paddleAngle - a;
		double h = Math.sqrt(x * x + y * y);
		double t = h * Math.sin(b);
		double d = h * Math.cos(b);
		if (x >= 0) {
			t *= -1;
		} else {
			d *= -1;
		}
		return new PointF((float) t, (float) d);
	}

	abstract class AbstractBonus {
		long expires;
		Drawable image;

		protected AbstractBonus(int duration) {
			this.expires = System.currentTimeMillis() + duration * 1000;
		}

		public abstract void remove();
	}

	class MultiplierBonus extends AbstractBonus {
		int value;

		public MultiplierBonus(int value) {
			super(value == 2 ? 20 : 15);
			this.value = value;
			this.image = value == 2 ? times2Image : times3Image;

			multiplier *= value;
		}

		public void remove() {
			multiplier /= value;
		}
	}

	class SpeedUpBonus extends AbstractBonus {
		public SpeedUpBonus() {
			super(10);
			this.image = speedUpImage;

			ballSpeed += 2;
			calculateBallDelta();
		}

		public void remove() {
			ballSpeed -= 2;
			calculateBallDelta();
		}
	}

	class MyThread extends Thread {
		MyThread(Context context) {
			Resources resources = context.getResources();
			bestScore = preferences.getInt("bestScore", 0);

			backgroundPaint = new Paint();
			backgroundPaint.setARGB(255, 0, 0, 0);
			paddlePaint = new Paint();
			paddlePaint.setARGB(255, 51, 181, 229);
			ballPaint = new Paint();
			ballPaint.setARGB(255, 137, 222, 57);
			plus10Image = resources.getDrawable(R.drawable.plus_10);
			assert plus10Image != null;
			plus10Image.setBounds(-30, -30, 30, 30);
			plus100Image = resources.getDrawable(R.drawable.plus_100);
			assert plus100Image != null;
			plus100Image.setBounds(-30, -30, 30, 30);
			times2Image = resources.getDrawable(R.drawable.times_2);
			assert times2Image != null;
			times2Image.setBounds(-30, -30, 30, 30);
			times3Image = resources.getDrawable(R.drawable.times_3);
			assert times3Image != null;
			times3Image.setBounds(-30, -30, 30, 30);
			speedUpImage = resources.getDrawable(R.drawable.speed_up);
			assert speedUpImage != null;
			speedUpImage.setBounds(-30, -30, 30, 30);

			targetHitSound = MediaPlayer.create(context, R.raw.increaseiconnumber);

			typeface = Typeface.createFromAsset(context.getAssets(), "font.ttf");
			scorePaint = new Paint();
			scorePaint.setColor(Color.WHITE);
			scorePaint.setTextSize(50);
			scorePaint.setTypeface(typeface);
			scorePaint.setTextAlign(Paint.Align.RIGHT);

			reset();
		}

		@Override
		public void run() {
			while (running) {
				Canvas c = null;
				try {
					c = surfaceHolder.lockCanvas();
					if (c == null) {
						return;
					}
					synchronized (surfaceHolder) {
						realGameLoop(c);
					}
				} finally {
					if (c != null) {
						surfaceHolder.unlockCanvasAndPost(c);
					}
				}
			}
		}
	}
}