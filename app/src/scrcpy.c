// ADDED BY km.yang(2021.02.02): jpg recording options 
#define _POSIX_C_SOURCE 200809L
#include <time.h>
#define ROUND(x) ((x)>=0?(double)((x)+0.5):(double)((x)-0.5))
#define NSEC_PER_SEC 1000000000L
#define MSEC_PER_SEC 1000L
// END

#include "scrcpy.h"

#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <libavformat/avformat.h>
#include <sys/time.h>
#include <SDL2/SDL.h>

#ifdef _WIN32
// not needed here, but winsock2.h must never be included AFTER windows.h
# include <winsock2.h>
# include <windows.h>
#endif

#include "config.h"
#include "command.h"
#include "common.h"
#include "compat.h"
#include "controller.h"
#include "decoder.h"
#include "device.h"
#include "events.h"
#include "file_handler.h"
#include "fps_counter.h"
#include "input_manager.h"
#include "recorder.h"
#include "screen.h"
#include "server.h"
#include "stream.h"
#include "tiny_xpm.h"
#include "video_buffer.h"
#include "util/lock.h"
#include "util/log.h"
#include "util/net.h"

static struct server server;
static struct screen screen = SCREEN_INITIALIZER;
static struct fps_counter fps_counter;
static struct video_buffer video_buffer;
static struct stream stream;
static struct decoder decoder;
static struct recorder recorder;
static struct controller controller;
static struct file_handler file_handler;

static struct input_manager input_manager = {
    .controller = &controller,
    .video_buffer = &video_buffer,
    .screen = &screen,
    .repeat = 0,

    // initialized later
    .prefer_text = false,
    .sdl_shortcut_mods = {
        .data = {0},
        .count = 0,
    },
};

#ifdef _WIN32
BOOL WINAPI windows_ctrl_handler(DWORD ctrl_type) {
    if (ctrl_type == CTRL_C_EVENT) {
        SDL_Event event;
        event.type = SDL_QUIT;
        SDL_PushEvent(&event);
        return TRUE;
    }
    return FALSE;
}
#endif // _WIN32

// init SDL and set appropriate hints
static bool
sdl_init_and_configure(bool display, const char *render_driver,
                       bool disable_screensaver) {
    uint32_t flags = display ? SDL_INIT_VIDEO : SDL_INIT_EVENTS;
    if (SDL_Init(flags)) {
        LOGC("Could not initialize SDL: %s", SDL_GetError());
        return false;
    }

    atexit(SDL_Quit);

#ifdef _WIN32
    // Clean up properly on Ctrl+C on Windows
    bool ok = SetConsoleCtrlHandler(windows_ctrl_handler, TRUE);
    if (!ok) {
        LOGW("Could not set Ctrl+C handler");
    }
#endif // _WIN32

    if (!display) {
        return true;
    }

    if (render_driver && !SDL_SetHint(SDL_HINT_RENDER_DRIVER, render_driver)) {
        LOGW("Could not set render driver");
    }

    // Linear filtering
    if (!SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "1")) {
        LOGW("Could not enable linear filtering");
    }

#ifdef SCRCPY_SDL_HAS_HINT_MOUSE_FOCUS_CLICKTHROUGH
    // Handle a click to gain focus as any other click
    if (!SDL_SetHint(SDL_HINT_MOUSE_FOCUS_CLICKTHROUGH, "1")) {
        LOGW("Could not enable mouse focus clickthrough");
    }
#endif

#ifdef SCRCPY_SDL_HAS_HINT_VIDEO_X11_NET_WM_BYPASS_COMPOSITOR
    // Disable compositor bypassing on X11
    if (!SDL_SetHint(SDL_HINT_VIDEO_X11_NET_WM_BYPASS_COMPOSITOR, "0")) {
        LOGW("Could not disable X11 compositor bypass");
    }
#endif

    // Do not minimize on focus loss
    if (!SDL_SetHint(SDL_HINT_VIDEO_MINIMIZE_ON_FOCUS_LOSS, "0")) {
        LOGW("Could not disable minimize on focus loss");
    }

    if (disable_screensaver) {
        LOGD("Screensaver disabled");
        SDL_DisableScreenSaver();
    } else {
        LOGD("Screensaver enabled");
        SDL_EnableScreenSaver();
    }

    return true;
}


#if defined(__APPLE__) || defined(__WINDOWS__)
# define CONTINUOUS_RESIZING_WORKAROUND
#endif

#ifdef CONTINUOUS_RESIZING_WORKAROUND
// On Windows and MacOS, resizing blocks the event loop, so resizing events are
// not triggered. As a workaround, handle them in an event handler.
//
// <https://bugzilla.libsdl.org/show_bug.cgi?id=2077>
// <https://stackoverflow.com/a/40693139/1987178>
static int
event_watcher(void *data, SDL_Event *event) {
    (void) data;
    if (event->type == SDL_WINDOWEVENT
            && event->window.event == SDL_WINDOWEVENT_RESIZED) {
        // In practice, it seems to always be called from the same thread in
        // that specific case. Anyway, it's just a workaround.
        screen_render(&screen, true);
    }
    return 0;
}
#endif

static bool
is_apk(const char *file) {
    const char *ext = strrchr(file, '.');
    return ext && !strcmp(ext, ".apk");
}

enum event_result {
    EVENT_RESULT_CONTINUE,
    EVENT_RESULT_STOPPED_BY_USER,
    EVENT_RESULT_STOPPED_BY_EOS,
};

// ADDED BY km.yang(2021.02.02): jpg recording options 
/**
 * https://github.com/kbehouse/h264tojpg/blob/857d9cbe8b23ae65225b57d3d43d851cf1fe84d3/h264tojpg.h
 */
static int
record_frames_as_jpeg(const AVFrame *pFrame, char *filename) {
    int width = pFrame->width;
    int height = pFrame->height;
    AVCodecContext *pCodeCtx = NULL;

    AVFormatContext *pFormatCtx = avformat_alloc_context();
    pFormatCtx->oformat = av_guess_format("mjpeg", NULL, NULL);

    if (avio_open(&pFormatCtx->pb, filename, AVIO_FLAG_READ_WRITE) < 0) {
        LOGW("Couldn't open output file.");
        return -1;
    }

    AVStream *pAVStream = avformat_new_stream(pFormatCtx, 0);
    if (pAVStream == NULL) {
        return -1;
    }

    AVCodecParameters *parameters = pAVStream->codecpar;
    parameters->codec_id = pFormatCtx->oformat->video_codec;
    parameters->codec_type = AVMEDIA_TYPE_VIDEO;
    parameters->format = AV_PIX_FMT_YUVJ420P;
    parameters->width = width; 
    parameters->height = height ;

    AVCodec *pCodec = avcodec_find_encoder(pAVStream->codecpar->codec_id);

    if (!pCodec) {
        LOGW("Could not find encoder\n");
        return -1;
    }

    pCodeCtx = avcodec_alloc_context3(pCodec);
    if (!pCodeCtx) {
        LOGE("Could not allocate video codec context\n");
        exit(1);
    }

    if ((avcodec_parameters_to_context(pCodeCtx, pAVStream->codecpar)) < 0) {
        LOGE("Failed to copy %s codec parameters to decoder context\n", av_get_media_type_string(AVMEDIA_TYPE_VIDEO));
        return -1;
    }

    pCodeCtx->time_base = (AVRational) {1, 25};

    if (avcodec_open2(pCodeCtx, pCodec, NULL) < 0) {
        LOGW("Could not open codec.");
        return -1;
    }

    int ret = avformat_write_header(pFormatCtx, NULL);
    if (ret < 0) {
        LOGW("write_header fail\n");
        return -1;
    }
    int y_size = width * height;

    //Encode
    AVPacket pkt;
    av_new_packet(&pkt, y_size * 3);

    ret = avcodec_send_frame(pCodeCtx, pFrame);
    if (ret < 0) {
        LOGW("Could not avcodec_send_frame.");
        return -1;
    }

    ret = avcodec_receive_packet(pCodeCtx, &pkt);
    if (ret < 0) {
        LOGW("Could not avcodec_receive_packet");
        return -1;
    }

    ret = av_write_frame(pFormatCtx, &pkt);

    if (ret < 0) {
        LOGW("Could not av_write_frame");
        return -1;
    }

    av_packet_unref(&pkt);

    av_write_trailer(pFormatCtx);

    avcodec_free_context(&pCodeCtx);
    //avcodec_close(pCodeCtx);
    avio_close(pFormatCtx->pb);
    avformat_free_context(pFormatCtx);

    return 0;
}

// static long long
// timespec_to_ns(struct timespec *tv) {
//     return ((long long)tv->tv_sec*NSEC_PER_SEC) + tv->tv_nsec;
// }

static long int
timespec_to_ms(struct timespec *tv) {
    return (long int)(((long int)tv->tv_sec*NSEC_PER_SEC) + tv->tv_nsec) / 1e6;
}

static long int
current_timestamp() {
    struct timespec tv;
    if(clock_gettime(CLOCK_REALTIME, &tv)) {
        return 0;
    }
    
    return timespec_to_ms(&tv);
    // return ((long int)tv.tv_sec*NSEC_PER_SEC) + tv.tv_nsec;
    // double epoch;
    // epoch = epoch_double(&tv);
    // epoch = ROUND(epoch*1e3);
    // return (long int) epoch;
}

static bool
format_filename(char *filename, const char *record_dir, int frame_number) {
    if (!record_dir) {
        return filename;
    }
    
    if (strstr(record_dir, "%d")) {
        if (strstr(record_dir, "%ld")) {
            // uint32_t now = SDL_GetTicks();
            // uint64_t now = (unsigned long)time(NULL);
            sprintf(filename, record_dir, frame_number, current_timestamp());
        } else {
            sprintf(filename, record_dir, frame_number);
        }
        return filename;
    } else if (strstr(record_dir, "%ld")) {
        // uint32_t now = SDL_GetTicks();
        // uint64_t now = (unsigned long)time(NULL);
        sprintf(filename, record_dir, current_timestamp());
        return filename;
    }
    sprintf(filename, record_dir, 0);
    return record_dir;
}
// END

static enum event_result
handle_event(SDL_Event *event, const struct scrcpy_options *options) {
    switch (event->type) {
        case EVENT_STREAM_STOPPED:
            LOGD("Video stream stopped");
            return EVENT_RESULT_STOPPED_BY_EOS;
        case SDL_QUIT:
            LOGD("User requested to quit");
            return EVENT_RESULT_STOPPED_BY_USER;
        case EVENT_NEW_FRAME:
            // ADDED BY km.yang(2021.02.02): jpg recording options 
            if (options->record_frames) {
                char *filename = SDL_malloc(256);
                format_filename(filename, options->record_dir, decoder.codec_ctx->frame_number);
                mutex_lock(video_buffer.mutex);
                const AVFrame *frame = video_buffer_consume_rendered_frame(&video_buffer);
                record_frames_as_jpeg(frame, filename);
                mutex_unlock(video_buffer.mutex);
                SDL_free(filename);
                // LOGD("handleEVENT_NEW_FRAME");
                break;
            }
            // END
            if (!screen.has_frame) {
                screen.has_frame = true;
                // this is the very first frame, show the window
                screen_show_window(&screen);
            }
            if (!screen_update_frame(&screen, &video_buffer)) {
                return EVENT_RESULT_CONTINUE;
            }
            break;
        case SDL_WINDOWEVENT:
            screen_handle_window_event(&screen, &event->window);
            break;
        case SDL_TEXTINPUT:
            if (!options->control) {
                break;
            }
            input_manager_process_text_input(&input_manager, &event->text);
            break;
        case SDL_KEYDOWN:
        case SDL_KEYUP:
            // some key events do not interact with the device, so process the
            // event even if control is disabled
            input_manager_process_key(&input_manager, &event->key);
            break;
        case SDL_MOUSEMOTION:
            if (!options->control) {
                break;
            }
            input_manager_process_mouse_motion(&input_manager, &event->motion);
            break;
        case SDL_MOUSEWHEEL:
            if (!options->control) {
                break;
            }
            input_manager_process_mouse_wheel(&input_manager, &event->wheel);
            break;
        case SDL_MOUSEBUTTONDOWN:
        case SDL_MOUSEBUTTONUP:
            // some mouse events do not interact with the device, so process
            // the event even if control is disabled
            input_manager_process_mouse_button(&input_manager, &event->button);
            break;
        case SDL_FINGERMOTION:
        case SDL_FINGERDOWN:
        case SDL_FINGERUP:
            input_manager_process_touch(&input_manager, &event->tfinger);
            break;
        case SDL_DROPFILE: {
            if (!options->control) {
                break;
            }
            file_handler_action_t action;
            if (is_apk(event->drop.file)) {
                action = ACTION_INSTALL_APK;
            } else {
                action = ACTION_PUSH_FILE;
            }
            file_handler_request(&file_handler, action, event->drop.file);
            break;
        }
    }
    return EVENT_RESULT_CONTINUE;
}

static bool
event_loop(const struct scrcpy_options *options) {
#ifdef CONTINUOUS_RESIZING_WORKAROUND
    if (options->display) {
        SDL_AddEventWatch(event_watcher, NULL);
    }
#endif
    SDL_Event event;
    while (SDL_WaitEvent(&event)) {
        enum event_result result = handle_event(&event, options);
        switch (result) {
            case EVENT_RESULT_STOPPED_BY_USER:
                return true;
            case EVENT_RESULT_STOPPED_BY_EOS:
                LOGW("Device disconnected");
                return false;
            case EVENT_RESULT_CONTINUE:
                break;
        }
    }
    return false;
}

static SDL_LogPriority
sdl_priority_from_av_level(int level) {
    switch (level) {
        case AV_LOG_PANIC:
        case AV_LOG_FATAL:
            return SDL_LOG_PRIORITY_CRITICAL;
        case AV_LOG_ERROR:
            return SDL_LOG_PRIORITY_ERROR;
        case AV_LOG_WARNING:
            return SDL_LOG_PRIORITY_WARN;
        case AV_LOG_INFO:
            return SDL_LOG_PRIORITY_INFO;
    }
    // do not forward others, which are too verbose
    return 0;
}

static void
av_log_callback(void *avcl, int level, const char *fmt, va_list vl) {
    (void) avcl;
    SDL_LogPriority priority = sdl_priority_from_av_level(level);
    if (priority == 0) {
        return;
    }
    char *local_fmt = SDL_malloc(strlen(fmt) + 10);
    if (!local_fmt) {
        LOGC("Could not allocate string");
        return;
    }
    // strcpy is safe here, the destination is large enough
    strcpy(local_fmt, "[FFmpeg] ");
    strcpy(local_fmt + 9, fmt);
    SDL_LogMessageV(SDL_LOG_CATEGORY_VIDEO, priority, local_fmt, vl);
    SDL_free(local_fmt);
}

bool
scrcpy(const struct scrcpy_options *options) {
    if (!server_init(&server)) {
        return false;
    }
    // ADDED BY km.yang(2021.02.17): attach timestamp to filename
    char ts_filename[256];
    // END
    bool ret = false;

    bool server_started = false;
    bool fps_counter_initialized = false;
    bool video_buffer_initialized = false;
    bool file_handler_initialized = false;
    bool recorder_initialized = false;
    bool stream_started = false;
    bool controller_initialized = false;
    bool controller_started = false;

    bool record = !!options->record_filename;
    struct server_params params = {
        //ADDED BY km.yang(2021.02.17): add an option for server
        .pushserver = options->pushserver,
        // END
        .log_level = options->log_level,
        .crop = options->crop,
        .port_range = options->port_range,
        .max_size = options->max_size,
        .bit_rate = options->bit_rate,
        .max_fps = options->max_fps,
        .lock_video_orientation = options->lock_video_orientation,
        .control = options->control,
        .display_id = options->display_id,
        .show_touches = options->show_touches,
        .stay_awake = options->stay_awake,
        .codec_options = options->codec_options,
        .encoder_name = options->encoder_name,
        .force_adb_forward = options->force_adb_forward,
    };
    if (!server_start(&server, options->serial, &params)) {
        goto end;
    }

    server_started = true;

    if (!sdl_init_and_configure(options->display, options->render_driver,
                                options->disable_screensaver)) {
        goto end;
    }

    if (!server_connect_to(&server)) {
        goto end;
    }

    char device_name[DEVICE_NAME_FIELD_LENGTH];
    struct size frame_size;

    // screenrecord does not send frames when the screen content does not
    // change therefore, we transmit the screen size before the video stream,
    // to be able to init the window immediately
    if (!device_read_info(server.video_socket, device_name, &frame_size)) {
        goto end;
    }

    struct decoder *dec = NULL;
    if (options->display) {
        if (!fps_counter_init(&fps_counter)) {
            goto end;
        }
        fps_counter_initialized = true;

        if (!video_buffer_init(&video_buffer, &fps_counter,
                               options->render_expired_frames)) {
            goto end;
        }
        video_buffer_initialized = true;

        if (options->control) {
            if (!file_handler_init(&file_handler, server.serial,
                                   options->push_target)) {
                goto end;
            }
            file_handler_initialized = true;
        }

        decoder_init(&decoder, &video_buffer);
        dec = &decoder;
    }
    // ADDED BY km.yang(2021.02.02): record jpeg
    else if(options->record_frames) {
        if (!fps_counter_init(&fps_counter)) {
            goto end;
        }
        fps_counter_initialized = true;

        if (!video_buffer_init(&video_buffer, &fps_counter,
                               options->render_expired_frames)) {
            goto end;
        }
        video_buffer_initialized = true;
        decoder_init(&decoder, &video_buffer);
        dec = &decoder;
    }
    // END

    struct recorder *rec = NULL;
    if (record) {
        if (!recorder_init(&recorder,
                           options->record_filename,
                           options->record_format,
                           frame_size)) {
            goto end;
        }
        rec = &recorder;
        recorder_initialized = true;
    }

    av_log_set_callback(av_log_callback);

    stream_init(&stream, server.video_socket, dec, rec);
    // ADDED BY km.yang(2021.02.22): add an option for taking a jpeg image file
    stream.only_one_frame = options->only_one_frame;
    // END
    // now we consumed the header values, the socket receives the video stream
    // start the stream
    if (!stream_start(&stream)) {
        goto end;
    }
    // ADDED BY km.yang(2021.02.17): attach timestamp to filename
    format_filename(ts_filename, options->record_filename, 0);
    // END
    stream_started = true;

    if (options->display) {
        if (options->control) {
            if (!controller_init(&controller, server.control_socket)) {
                goto end;
            }
            controller_initialized = true;

            if (!controller_start(&controller)) {
                goto end;
            }
            controller_started = true;
        }

        const char *window_title =
            options->window_title ? options->window_title : device_name;

        if (!screen_init_rendering(&screen, window_title, frame_size,
                                   options->always_on_top, options->window_x,
                                   options->window_y, options->window_width,
                                   options->window_height,
                                   options->window_borderless,
                                   options->rotation, options->mipmaps)) {
            goto end;
        }

        if (options->turn_screen_off) {
            struct control_msg msg;
            msg.type = CONTROL_MSG_TYPE_SET_SCREEN_POWER_MODE;
            msg.set_screen_power_mode.mode = SCREEN_POWER_MODE_OFF;

            if (!controller_push_msg(&controller, &msg)) {
                LOGW("Could not request 'set screen power mode'");
            }
        }

        if (options->fullscreen) {
            screen_switch_fullscreen(&screen);
        }
    }

    input_manager_init(&input_manager, options);

    ret = event_loop(options);
    // ADDED BY km.yang(2021.02.17): attach timestamp to filename
    // move the recred mp4 file to ts_filename
    if (rename(options->record_filename, ts_filename) != 0) {
        LOGE("Could not rename the record file from %s to %s", options->record_filename, ts_filename);
    }
    // END
    LOGD("quit...");

    screen_destroy(&screen);

end:
    // stop stream and controller so that they don't continue once their socket
    // is shutdown
    if (stream_started) {
        stream_stop(&stream);
    }
    if (controller_started) {
        controller_stop(&controller);
    }
    if (file_handler_initialized) {
        file_handler_stop(&file_handler);
    }
    if (fps_counter_initialized) {
        fps_counter_interrupt(&fps_counter);
    }

    if (server_started) {
        // shutdown the sockets and kill the server
        server_stop(&server);
    }

    // now that the sockets are shutdown, the stream and controller are
    // interrupted, we can join them
    if (stream_started) {
        stream_join(&stream);
    }
    if (controller_started) {
        controller_join(&controller);
    }
    if (controller_initialized) {
        controller_destroy(&controller);
    }

    if (recorder_initialized) {
        recorder_destroy(&recorder);
    }

    if (file_handler_initialized) {
        file_handler_join(&file_handler);
        file_handler_destroy(&file_handler);
    }

    if (video_buffer_initialized) {
        video_buffer_destroy(&video_buffer);
    }

    if (fps_counter_initialized) {
        fps_counter_join(&fps_counter);
        fps_counter_destroy(&fps_counter);
    }

    server_destroy(&server);

    return ret;
}
