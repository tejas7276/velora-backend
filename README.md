# ⚡ Velora — Distributed AI Job Processing Platform

> **velox** (Latin: swift) + **ora** (Latin: aura) = *The essence of speed.*

A production-grade distributed system for queuing, processing, and monitoring AI workloads at scale. Built with Spring Boot, RabbitMQ, PostgreSQL, and React.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         React Frontend                        │
│              Register → Login → Dashboard → Jobs             │
└──────────────────────┬──────────────────────────────────────┘
                       │ REST API (JWT Auth)
┌──────────────────────▼──────────────────────────────────────┐
│                    Spring Boot Backend                        │
│   JobController → JobService → RabbitMQ Queue                │
│                                     │                         │
│                              JobWorkerConsumer                │
│                                     │                         │
│                            DocumentQAEngine (RAG)            │
│                                     │                         │
│                            OpenAIClient → Groq API           │
└──────┬──────────────────────────────────────────────────────┘
       │
┌──────▼──────┐    ┌──────────────┐    ┌──────────────────────┐
│ PostgreSQL  │    │   RabbitMQ   │    │   Gmail SMTP         │
│  (Jobs DB)  │    │  (Job Queue) │    │   (Email Service)    │
└─────────────┘    └──────────────┘    └──────────────────────┘
```

---

## ✨ Features

### AI Job Types (17)
| Category | Job Types |
|----------|-----------|
| **Core** | AI Analysis, Summarize, Sentiment Analysis |
| **Extraction** | Extract Keywords, Classify Text, Translate |
| **Document** | Question & Answer, Compare Documents, Generate Report |
| **HR & Career** | Resume Score, Interview Prep, JD Match, LinkedIn Bio |
| **Professional** | Email Writer, Meeting Summary, Bug Explainer, Code Review |

### System Features
- ⚡ **Priority Queue** — CRITICAL / HIGH / MEDIUM / LOW job routing
- 🗓️ **Job Scheduling** — schedule jobs to run at any future time
- 📊 **Real-time Monitoring** — live status, processing time, error logs
- 🔁 **Auto Retry** — failed jobs retry up to 3 times automatically
- 📄 **PDF Support** — upload PDFs, text extracted and used as AI context
- 🧠 **RAG Pipeline** — document chunking, intent detection, grounding validation
- 📧 **Email Notifications** — welcome email, forgot password OTP
- 🔐 **JWT Authentication** — secure token-based auth, no session state

---

## 🚀 Quick Start

### Option A — Docker (recommended, one command)

```bash
# Clone
git clone https://github.com/yourusername/velora.git
cd velora

# Copy env file and fill in your API keys
cp .env.example .env

# Start everything (PostgreSQL + RabbitMQ + Spring Boot)
docker-compose up -d

# Check logs
docker-compose logs -f app
```

App available at: `http://localhost:8001/api`
API docs at: `http://localhost:8001/api/swagger-ui.html`
RabbitMQ dashboard: `http://localhost:15672` (guest/guest)

---

### Option B — Manual Setup

**Prerequisites:**
- Java 17+
- Maven 3.8+
- PostgreSQL 14+
- RabbitMQ 3+

**Backend:**
```bash
cd velora-backend

# Create database
psql -U postgres -c "CREATE DATABASE velora;"
psql -U postgres -c "CREATE USER jobflowuser WITH PASSWORD '1234';"
psql -U postgres -c "GRANT ALL ON DATABASE velora TO jobflowuser;"

# Configure (edit src/main/resources/application.properties)
# Set: spring.mail.username, spring.mail.password, openai.api.key

# Run
./mvnw spring-boot:run
```

**Frontend:**
```bash
cd velora-frontend
npm install
npm run dev
```

---

## ⚙️ Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `openai.api.key` | Groq API key | required |
| `openai.model` | AI model name | `llama-3.3-70b-versatile` |
| `spring.mail.username` | Gmail address | required for email |
| `spring.mail.password` | Gmail app password | required for email |
| `jwt.secret` | JWT signing secret | change in production |
| `app.upload.dir` | PDF upload directory | `uploads` |

**Getting a Groq API key:**
1. Go to [console.groq.com](https://console.groq.com)
2. Sign up free
3. Create API key
4. Paste into `application.properties`

**Gmail App Password:**
1. Enable 2FA on your Google account
2. Go to Google Account → Security → App Passwords
3. Generate password for "Mail"
4. Use that 16-character password (not your regular password)

---

## 🧪 Running Tests

```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest=JobServiceTest

# With coverage report
mvn test jacoco:report
# Open: target/site/jacoco/index.html
```

Tests use an H2 in-memory database — no PostgreSQL or RabbitMQ needed.

---

## 📡 API Reference

Full interactive docs: `http://localhost:8001/api/swagger-ui.html`

### Authentication

```bash
# Register
POST /api/auth/register
{"name": "Tejas", "email": "tejas@example.com", "password": "secret123"}

# Login → returns JWT token
POST /api/auth/login
{"email": "tejas@example.com", "password": "secret123"}
```

### Jobs

```bash
# Create job (text)
POST /api/jobs
Content-Type: multipart/form-data
jobType=SUMMARIZE&payload=Your long text here&priority=HIGH

# Create job (PDF)
POST /api/jobs
Content-Type: multipart/form-data
jobType=RESUME_SCORE&payload=Score this resume&file=@resume.pdf

# Get all jobs
GET /api/jobs

# Get job by ID
GET /api/jobs/{id}

# Retry failed job
POST /api/jobs/{id}/retry

# Cancel job
PUT /api/jobs/{id}/cancel
```

All endpoints require: `Authorization: Bearer <jwt-token>`

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 17, Spring Boot 3.2, Spring Security |
| Database | PostgreSQL 15, Spring Data JPA, Hibernate |
| Queue | RabbitMQ 3, Spring AMQP |
| AI | Groq API (Llama 3.3 70B), LangChain4j patterns |
| Email | JavaMail, Gmail SMTP |
| Frontend | React 18, Vite, Tailwind CSS, Framer Motion |
| Auth | JWT (jjwt), BCrypt |
| Docs | SpringDoc OpenAPI 3 (Swagger UI) |
| Containers | Docker, Docker Compose |
| Testing | JUnit 5, Mockito, MockMvc, H2 |

---

## 📁 Project Structure

```
velora/
├── src/
│   ├── main/java/com/velora/aijobflow/
│   │   ├── config/          # Security, RabbitMQ, Swagger config
│   │   ├── controller/      # REST API endpoints
│   │   ├── model/           # JPA entities (Job, User, Attachment)
│   │   ├── repository/      # Spring Data JPA repos
│   │   ├── security/        # JWT filter, SecurityUtils
│   │   ├── service/         # Business logic
│   │   ├── util/            # OpenAIClient, DocumentQAEngine, PromptRouter
│   │   └── worker/          # RabbitMQ consumer
│   ├── main/resources/
│   │   └── application.properties
│   └── test/
│       ├── java/             # JUnit tests
│       └── resources/
│           └── application-test.properties
├── frontend/                 # React app
├── Dockerfile
├── docker-compose.yml
└── README.md
```

---

## 🔒 Security Notes

- Passwords hashed with BCrypt (cost factor 12)
- JWT tokens expire in 24 hours
- User ID extracted from JWT server-side (never trusted from client)
- PDF uploads stored with UUID-prefixed filenames
- SQL injection prevented by JPA parameterized queries
- CORS configured for frontend origin only

---

## 🤝 Contributing

1. Fork the repo
2. Create feature branch: `git checkout -b feature/your-feature`
3. Commit: `git commit -m 'Add your feature'`
4. Push: `git push origin feature/your-feature`
5. Open a Pull Request

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

<div align="center">
  Built with ❤️ by Tejas Shinde
  <br/>
  <sub>velox · swift · ora · aura — The essence of speed.</sub>
</div>