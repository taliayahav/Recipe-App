## Getting Started

Welcome to the VS Code Java world. Here is a guideline to help you get started to write Java code in Visual Studio Code.

## Folder Structure

The workspace contains two folders by default, where:

- `src`: the folder to maintain sources
- `lib`: the folder to maintain dependencies

Meanwhile, the compiled output files will be generated in the `bin` folder by default.

> If you want to customize the folder structure, open `.vscode/settings.json` and update the related settings there.

## Dependency Management

The `JAVA PROJECTS` view allows you to manage your dependencies. More details can be found [here](https://github.com/microsoft/vscode-java-dependency#manage-dependencies).

## Recipe App (backend + frontend)

This workspace now includes a small Recipe App split into `backend/` (Java + Javalin + SQLite) and `frontend/` (React + Vite).

Backend (Java):
- Location: `backend/`
- Build & run:

```bash
cd backend
mvn package
java -jar target/recipe-backend-1.0.0-jar-with-dependencies.jar
```

The backend listens on port 7001 and exposes:
- GET /recipes -> list all recipes
- POST /recipes -> create a recipe (JSON body)

Frontend (React + Vite):
- Location: `frontend/`
- Install & run:

```bash
cd frontend
npm install
npm run dev
```

The frontend expects the backend at `http://localhost:7000`.

The frontend expects the backend at `http://localhost:7001` (or uses `VITE_BACKEND_URL` when built).

Docker (optional) - build and run both services with Docker Compose:

```bash
docker-compose build
docker-compose up
```

- Backend will be available at http://localhost:7001
- Frontend will be available at http://localhost:5174

The frontend image reads `VITE_BACKEND_URL` from compose, which points to the internal Docker service name `backend`.
