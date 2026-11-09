package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/app"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/auth"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads/lxruntime"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"golang.org/x/term"
)

var version = "dev"

func main() {
	// A script worker starts before configuration, migrations, keys, or HTTP.
	if len(os.Args) == 2 && os.Args[1] == "--lx-worker" {
		if lxruntime.Worker(context.Background(), os.Stdin, os.Stdout) != nil {
			os.Exit(1)
		}
		return
	}
	log := slog.New(slog.NewJSONHandler(os.Stderr, nil))
	if err := run(log); err != nil {
		log.Error("backend stopped", "reason", err.Error())
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	reset := flag.Bool("reset-admin", false, "recover administrator on the deployment host")
	showVersion := flag.Bool("version", false, "print version")
	flag.Parse()
	if flag.NArg() != 0 {
		return errors.New("unexpected positional arguments")
	}
	if *showVersion {
		fmt.Println(version)
		return nil
	}
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	db, err := store.Open(ctx, cfg)
	if err != nil {
		return err
	}
	defer db.Close()
	if *reset {
		return recoverAdministrator(ctx, db)
	}
	application, err := app.New(cfg, db.DB, log)
	if err != nil {
		return errors.New("application initialization failed; check configuration and matching authentication key")
	}
	defer application.Close()
	listener, err := net.Listen("tcp", cfg.Address)
	if err != nil {
		return errors.New("cannot bind business HTTP listener")
	}
	server := app.Server(cfg.Address, application.Router)
	var diagnostic *http.Server
	if cfg.DiagnosticsAddress != "" {
		dl, e := net.Listen("tcp", cfg.DiagnosticsAddress)
		if e != nil {
			listener.Close()
			return errors.New("cannot bind diagnostics listener")
		}
		diagnostic = app.Server(cfg.DiagnosticsAddress, application.Diagnostics())
		go func() { _ = diagnostic.Serve(dl) }()
	}
	failures := make(chan error, 1)
	go func() { failures <- server.Serve(listener) }()
	log.Info("backend listening", "address", listener.Addr().String(), "version", version)
	select {
	case err = <-failures:
		if !errors.Is(err, http.ErrServerClosed) {
			return errors.New("business HTTP listener failed")
		}
	case <-ctx.Done():
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err = server.Shutdown(shutdown); err != nil {
		_ = server.Close()
	}
	if diagnostic != nil {
		_ = diagnostic.Shutdown(shutdown)
	}
	return nil
}

func recoverAdministrator(ctx context.Context, db *store.Database) error {
	fd := int(os.Stdin.Fd())
	if !term.IsTerminal(fd) {
		return errors.New("administrator recovery requires an interactive TTY")
	}
	fmt.Fprint(os.Stderr, "输入 RESET 确认管理员恢复：")
	confirmation, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil || strings.TrimSpace(confirmation) != "RESET" {
		return errors.New("recovery cancelled")
	}
	password, err := readHiddenPassword(fd, "新密码：")
	if err != nil {
		return errors.New("cannot read password")
	}
	defer clear(password)
	confirmationBytes, err := readHiddenPassword(fd, "再次输入新密码：")
	if err != nil {
		return errors.New("cannot read password")
	}
	defer clear(confirmationBytes)
	if string(password) != string(confirmationBytes) {
		return errors.New("passwords do not match")
	}
	if err = auth.ResetAdministrator(ctx, db.DB, string(password)); err != nil {
		return errors.New("administrator recovery failed; check password requirements and database")
	}
	fmt.Fprintln(os.Stderr, "管理员恢复完成，所有会话已撤销。")
	return nil
}
