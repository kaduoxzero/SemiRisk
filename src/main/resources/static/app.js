/* 供应链风险智能管理系统 - 共享脚本 */

class ParticleSystem {
  constructor(canvasId) {
    this.canvas = document.getElementById(canvasId);
    if (!this.canvas) return;
    this.ctx = this.canvas.getContext('2d');
    this.particles = [];
    this.numberOfParticles = 100;
    this.colors = ['#3b82f6', '#8b5cf6', '#06b6d4'];
    
    this.init();
    this.animate();
    window.addEventListener('resize', () => this.resize());
  }

  init() {
    this.resize();
    for (let i = 0; i < this.numberOfParticles; i++) {
      this.particles.push(new Particle(this.canvas, this.colors));
    }
  }

  resize() {
    this.canvas.width = window.innerWidth;
    this.canvas.height = window.innerHeight;
  }

  animate() {
    this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    for (let i = 0; i < this.particles.length; i++) {
      this.particles[i].update();
      this.particles[i].draw(this.ctx);
      
      for (let j = i; j < this.particles.length; j++) {
        const dx = this.particles[i].x - this.particles[j].x;
        const dy = this.particles[i].y - this.particles[j].y;
        const distance = Math.sqrt(dx * dx + dy * dy);
        
        if (distance < 150) {
          this.ctx.beginPath();
          this.ctx.strokeStyle = `rgba(59, 130, 246, ${0.15 * (1 - distance/150)})`;
          this.ctx.lineWidth = 1;
          this.ctx.moveTo(this.particles[i].x, this.particles[i].y);
          this.ctx.lineTo(this.particles[j].x, this.particles[j].y);
          this.ctx.stroke();
          this.ctx.closePath();
        }
      }
    }
    requestAnimationFrame(() => this.animate());
  }
}

class Particle {
  constructor(canvas, colors) {
    this.canvas = canvas;
    this.x = Math.random() * this.canvas.width;
    this.y = Math.random() * this.canvas.height;
    this.size = Math.random() * 2 + 1;
    this.speedX = Math.random() * 0.5 - 0.25;
    this.speedY = Math.random() * 0.5 - 0.25;
    this.color = colors[Math.floor(Math.random() * colors.length)];
  }

  update() {
    this.x += this.speedX;
    this.y += this.speedY;

    if (this.x > this.canvas.width) this.x = 0;
    else if (this.x < 0) this.x = this.canvas.width;
    if (this.y > this.canvas.height) this.y = 0;
    else if (this.y < 0) this.y = this.canvas.height;
  }

  draw(ctx) {
    ctx.fillStyle = this.color;
    ctx.beginPath();
    ctx.arc(this.x, this.y, this.size, 0, Math.PI * 2);
    ctx.fill();
  }
}

// 实时时钟
function updateClock() {
  const now = new Date();
  const timeStr = now.toLocaleTimeString('zh-CN', { hour12: false });
  const dateStr = now.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).replace(/\//g, '-');
  const clockEl = document.getElementById('current-time');
  if (clockEl) {
    clockEl.innerText = `${dateStr} ${timeStr}`;
  }
}

// 初始化
document.addEventListener('DOMContentLoaded', () => {
  new ParticleSystem('particle-canvas');
  setInterval(updateClock, 1000);
  updateClock();

  // 导航折叠逻辑
  const navToggle = document.getElementById('nav-toggle');
  const sidebar = document.getElementById('sidebar');
  if (navToggle && sidebar) {
    navToggle.addEventListener('click', () => {
      sidebar.classList.toggle('w-64');
      sidebar.classList.toggle('w-20');
      document.querySelectorAll('.nav-text').forEach(el => el.classList.toggle('hidden'));
    });
  }
});

// 通用 Toast 通知
function showToast(message, type = 'info') {
  const toast = document.createElement('div');
  toast.className = `fixed bottom-4 right-4 px-6 py-3 rounded-lg shadow-lg z-50 transition-all duration-300 transform translate-y-20 opacity-0 hud-card`;
  
  const colors = {
    success: 'border-green-500 text-green-400',
    danger: 'border-red-500 text-red-400',
    warning: 'border-yellow-500 text-yellow-400',
    info: 'border-blue-500 text-blue-400'
  };
  
  toast.classList.add(...colors[type].split(' '));
  toast.innerHTML = `
    <div class="flex items-center space-x-2">
      <iconify-icon icon="lucide:info" class="text-xl"></iconify-icon>
      <span class="font-medium">${message}</span>
    </div>
  `;
  
  document.body.appendChild(toast);
  
  setTimeout(() => {
    toast.classList.remove('translate-y-20', 'opacity-0');
  }, 100);
  
  setTimeout(() => {
    toast.classList.add('translate-y-20', 'opacity-0');
    setTimeout(() => toast.remove(), 300);
  }, 3000);
}
