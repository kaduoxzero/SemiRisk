<template>
  <canvas ref="canvasRef" class="particle-canvas"></canvas>
</template>

<script setup name="CyberParticles">
import { ref, onMounted, onBeforeUnmount } from 'vue'

const canvasRef = ref(null)
let animationFrameId = null
const colors = ['#3b82f6', '#8b5cf6', '#06b6d4']
let particles = []
const numberOfParticles = 60

class Particle {
  constructor(canvas) {
    this.canvas = canvas
    this.x = Math.random() * canvas.width
    this.y = Math.random() * canvas.height
    this.size = Math.random() * 1.5 + 1
    this.speedX = Math.random() * 0.4 - 0.2
    this.speedY = Math.random() * 0.4 - 0.2
    this.color = colors[Math.floor(Math.random() * colors.length)]
  }

  update() {
    this.x += this.speedX
    this.y += this.speedY

    if (this.x > this.canvas.width) this.x = 0
    else if (this.x < 0) this.x = this.canvas.width
    if (this.y > this.canvas.height) this.y = 0
    else if (this.y < 0) this.y = this.canvas.height
  }

  draw(ctx) {
    ctx.fillStyle = this.color
    ctx.beginPath()
    ctx.arc(this.x, this.y, this.size, 0, Math.PI * 2)
    ctx.fill()
  }
}

const resizeCanvas = () => {
  const canvas = canvasRef.value
  if (canvas) {
    canvas.width = window.innerWidth
    canvas.height = window.innerHeight
  }
}

const animate = (ctx, canvas) => {
  ctx.clearRect(0, 0, canvas.width, canvas.height)

  for (let i = 0; i < particles.length; i++) {
    particles[i].update()
    particles[i].draw(ctx)

    for (let j = i; j < particles.length; j++) {
      const dx = particles[i].x - particles[j].x
      const dy = particles[i].y - particles[j].y
      const distance = Math.sqrt(dx * dx + dy * dy)

      if (distance < 120) {
        ctx.beginPath()
        ctx.strokeStyle = `rgba(59, 130, 246, ${0.12 * (1 - distance / 120)})`
        ctx.lineWidth = 0.8
        ctx.moveTo(particles[i].x, particles[i].y)
        ctx.lineTo(particles[j].x, particles[j].y)
        ctx.stroke()
        ctx.closePath()
      }
    }
  }
  animationFrameId = requestAnimationFrame(() => animate(ctx, canvas))
}

onMounted(() => {
  const canvas = canvasRef.value
  if (canvas) {
    const ctx = canvas.getContext('2d')
    resizeCanvas()
    window.addEventListener('resize', resizeCanvas)

    particles = []
    for (let i = 0; i < numberOfParticles; i++) {
      particles.push(new Particle(canvas))
    }
    animate(ctx, canvas)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCanvas)
  if (animationFrameId) {
    cancelAnimationFrame(animationFrameId)
  }
})
</script>

<style scoped>
.particle-canvas {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: -1;
  pointer-events: none;
}
</style>
